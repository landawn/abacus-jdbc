/*
 * Copyright (c) 2025, Haiyang Li.
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
import com.landawn.abacus.jdbc.JdbcUtil;
import com.landawn.abacus.jdbc.OP;
import com.landawn.abacus.util.RegExUtil;
import com.mysql.cj.x.protobuf.MysqlxCrud.Delete;
import com.mysql.cj.x.protobuf.MysqlxCrud.Insert;
import com.mysql.cj.x.protobuf.MysqlxCrud.Update;

/**
 * Defines a generic SQL query operation for a DAO method.
 * This annotation provides a flexible way to execute any type of SQL statement (SELECT, INSERT, UPDATE, DELETE, or stored procedure calls)
 * with comprehensive configuration options for execution parameters, batching, and result handling.
 *
 * <p>The {@code Query} annotation is a general-purpose alternative to specialized annotations like {@link Select}, {@link Insert},
 * {@link Update}, {@link Delete}, and {@link Call}. It offers the same capabilities but with a unified interface, making it ideal
 * for scenarios where you want consistent annotation usage across different operation types or when the SQL type might vary dynamically.</p>
 *
 * <p>Key features:</p>
 * <ul>
 *   <li>Support for all SQL statement types (SELECT, INSERT, UPDATE, DELETE)</li>
 *   <li>Inline SQL using the {@link #value()} attribute</li>
 *   <li>Reference to external SQL using the {@link #id()} attribute</li>
 *   <li>Flexible operation type specification via {@link #op()}</li>
 *   <li>Batch processing capabilities for bulk operations</li>
 *   <li>Query timeout and fetch size configuration</li>
 *   <li>Dynamic SQL with template variables</li>
 *   <li>Automatic timestamp injection</li>
 * </ul>
 *
 * <p>Basic usage examples:</p>
 * <pre>{@code
 * public interface UserDao extends CrudDao<User, Long> {
 *     // SELECT operation
 *     @Query("SELECT * FROM users WHERE status = :status")
 *     List<User> findByStatus(@Bind("status") String status);
 *
 *     // INSERT operation
 *     @Query("INSERT INTO users (name, email) VALUES (:name, :email)")
 *     int insertUser(@Bind("name") String name, @Bind("email") String email);
 *
 *     // UPDATE operation
 *     @Query(value = "UPDATE users SET last_login = :now WHERE id = :id", timestamped = true)
 *     int updateLastLogin(@Bind("id") Long id);
 *
 *     // DELETE operation
 *     @Query("DELETE FROM users WHERE inactive_since < :date")
 *     int deleteInactiveUsers(@Bind("date") Date date);
 *
 *     // Using external SQL mapper
 *     @Query(id = "complexUserQuery")
 *     List<User> findUsersByComplexCriteria(@Bind("criteria") SearchCriteria criteria);
 * }
 * }</pre>
 *
 * <p>Advanced usage examples:</p>
 * <pre>{@code
 * public interface AdvancedDao {
 *     // Batch operation with custom batch size
 *     @Query(value = "INSERT INTO logs (timestamp, message) VALUES (:timestamp, :message)",
 *            isBatch = true, batchSize = 500)
 *     int[] batchInsertLogs(@Bind("timestamp") List<Date> timestamps,
 *                          @Bind("message") List<String> messages);
 *
 *     // Large result set with custom fetch size
 *     @Query(value = "SELECT * FROM large_table", fetchSize = 1000)
 *     Stream<Record> streamLargeTable();
 *
 *     // Dynamic SQL with Define parameters
 *     @Query(value = "SELECT * FROM {table} WHERE {column} = :value",
 *            hasDefineWithNamedParameter = true)
 *     List<Map<String, Object>> dynamicQuery(@Define("table") String table,
 *                                           @Define("column") String column,
 *                                           @Bind("value") Object value);
 *
 *     // Query with timeout for long-running operations
 *     @Query(value = "SELECT * FROM users u JOIN orders o ON u.id = o.user_id",
 *            queryTimeout = 30)
 *     List<UserWithOrders> findUsersWithOrders();
 *
 *     // Aggregate query with specific operation type
 *     @Query(value = "SELECT COUNT(*) FROM users WHERE active = true",
 *            op = OP.queryForSingle)
 *     long countActiveUsers();
 *
 *     // Existence check
 *     @Query(value = "SELECT 1 FROM users WHERE email = :email LIMIT 1",
 *            op = OP.exists)
 *     boolean emailExists(@Bind("email") String email);
 * }
 * }</pre>
 *
 * <p>Parameter binding:</p>
 * <ul>
 *   <li>Named parameters using {@code :paramName} syntax</li>
 *   <li>Use {@link Bind} annotation to map method parameters to SQL parameters</li>
 *   <li>Entity properties are automatically bound when passing entity objects</li>
 *   <li>Nested property access via dot notation (e.g., {@code :user.email})</li>
 *   <li>Template variables using {@code {variableName}} with {@link Define} annotation</li>
 * </ul>
 *
 * <p>Return type handling:</p>
 * <p>The framework automatically adapts to the method's return type:</p>
 * <ul>
 *   <li>{@code void} - No return value</li>
 *   <li>{@code int/long} - Number of affected rows (for UPDATE/INSERT/DELETE)</li>
 *   <li>{@code boolean} - For existence checks when using {@code op = OP.exists}</li>
 *   <li>Single object - First result or unique result</li>
 *   <li>{@code Optional<T>} - Empty if no result found</li>
 *   <li>{@code List<T>} - Multiple results</li>
 *   <li>{@code Stream<T>} - Lazy streaming of results</li>
 *   <li>{@code int[]} - Batch operation results</li>
 *   <li>Custom types via {@link Handler} annotation</li>
 * </ul>
 *
 * @see Bind
 * @see Define
 * @see DefineList
 * @see SqlMapper
 * @see Handler
 * @see OP
 * @see OutParameter
 * @see OutParameterList
 * @see <a href="https://stackoverflow.com/questions/1820908/how-to-turn-off-the-eclipse-code-formatter-for-certain-sections-of-java-code">How to turn off the Eclipse code formatter for certain sections of Java code?</a>
 */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.METHOD)
public @interface Query {

    /**
     * Specifies the SQL statement to execute.
     * This can be any valid SQL statement including SELECT, INSERT, UPDATE, DELETE, or even stored procedure calls.
     *
     * <p>The SQL can include:</p>
     * <ul>
     *   <li>Named parameters using {@code :paramName} syntax for value binding</li>
     *   <li>Template variables using {@code {variableName}} syntax when {@link #hasDefineWithNamedParameter()} is {@code true}</li>
     *   <li>Standard SQL features like JOINs, subqueries, CTEs (Common Table Expressions), window functions, etc.</li>
     *   <li>Database-specific SQL extensions and functions</li>
     * </ul>
     *
     * <p>Named parameter examples:</p>
     * <pre>{@code
     * // Simple parameter binding
     * @Query("SELECT * FROM users WHERE age > :minAge")
     * List<User> findByAge(@Bind("minAge") int minAge);
     *
     * // Multiple parameters
     * @Query("SELECT * FROM users WHERE age BETWEEN :minAge AND :maxAge")
     * List<User> findByAgeRange(@Bind("minAge") int min, @Bind("maxAge") int max);
     *
     * // Nested property access
     * @Query("SELECT * FROM orders WHERE user_id = :user.id AND status = :status")
     * List<Order> findOrders(@Bind("user") User user, @Bind("status") String status);
     *
     * // IN clause with collection
     * @Query("SELECT * FROM users WHERE id IN (:ids)")
     * List<User> findByIds(@Bind("ids") List<Long> ids);
     * }</pre>
     *
     * <p>Complex SQL examples:</p>
     * <pre>{@code
     * // JOIN with aggregation
     * @Query("SELECT u.*, COUNT(o.id) as order_count " +
     *               "FROM users u LEFT JOIN orders o ON u.id = o.user_id " +
     *               "WHERE u.created_date > :startDate " +
     *               "GROUP BY u.id HAVING COUNT(o.id) > :minOrders")
     * List<UserStats> findUserStats(@Bind("startDate") Date startDate,
     *                               @Bind("minOrders") int minOrders);
     *
     * // Common Table Expression (CTE)
     * @Query("WITH recent_orders AS ( " +
     *               "  SELECT user_id, COUNT(*) as order_count " +
     *               "  FROM orders WHERE order_date > :since " +
     *               "  GROUP BY user_id " +
     *               ") " +
     *               "SELECT u.*, ro.order_count " +
     *               "FROM users u JOIN recent_orders ro ON u.id = ro.user_id")
     * List<UserOrderSummary> findActiveUserSummary(@Bind("since") Date since);
     *
     * // Window function
     * @Query("SELECT *, ROW_NUMBER() OVER (PARTITION BY department ORDER BY salary DESC) as rank " +
     *               "FROM employees WHERE department = :dept")
     * List<Employee> rankEmployeesByDepartment(@Bind("dept") String department);
     * }</pre>
     *
     * <p>Note: Either {@code value} or {@link #id()} should be specified, but not both.
     * If neither is specified, the framework may attempt to derive the SQL based on the method name and entity mapping.</p>
     *
     * @return the SQL statement to execute, or empty string if using {@link #id()}
     */
    String[] value() default {}; // default ""; // inline SQL statements;

    /**
     * Specifies the ID of a SQL statement defined in an external SQL mapper.
     * This allows SQL to be defined separately from Java code, enabling better organization and reusability.
     * It must be a valid Java identifier as per {@link RegExUtil#JAVA_IDENTIFIER_MATCHER}.
     *
     * <p>The SQL mapper can be specified at the DAO interface level using the {@link SqlMapper} annotation,
     * which points to XML files or other configuration sources containing SQL definitions.</p>
     *
     * <p>Benefits of using external SQL mappers:</p>
     * <ul>
     *   <li>Better separation of concerns (SQL separate from Java)</li>
     *   <li>Easier maintenance of complex multi-line SQL statements</li>
     *   <li>SQL reusability across multiple DAO methods or interfaces</li>
     *   <li>Simplified SQL formatting and readability</li>
     *   <li>Support for SQL variants based on database type</li>
     *   <li>Centralized SQL management and version control</li>
     * </ul>
     *
     * <p>Usage example:</p>
     * <pre>{@code
     * // In DAO interface
     * @SqlMapper("user-queries.xml")
     * public interface UserDao extends CrudDao<User, Long> {
     *     @Query(id = "findUsersByComplexCriteria")
     *     List<User> findUsers(@Bind("criteria") SearchCriteria criteria);
     *
     *     @Query(id = "updateUserStatus")
     *     int updateStatus(@Bind("userId") Long userId, @Bind("status") String status);
     *
     *     @Query(id = "getUserStatistics")
     *     UserStats getStatistics(@Bind("startDate") Date start, @Bind("endDate") Date end);
     * }
     *
     * // In user-queries.xml or similar SQL mapper file:
     * // <sql id="findUsersByComplexCriteria">
     * //   SELECT u.*, p.profile_data
     * //   FROM users u
     * //   LEFT JOIN profiles p ON u.id = p.user_id
     * //   WHERE ...complex conditions...
     * // </sql>
     * }</pre>
     *
     * <p>Note: Either {@link #value()} or {@code id} should be specified, but not both.</p>
     *
     * @return the SQL statement ID from the SQL mapper, or empty string if using {@link #value()}
     * @see RegExUtil.JAVA_IDENTIFIER_MATCHER
     */
    String[] id() default {}; // default ""; // id defined SqlMapper

    /**
     * Specifies the operation type for this query.
     * This determines how the SQL statement is executed and how results are processed.
     *
     * <p>The operation type influences:</p>
     * <ul>
     *   <li>Result set processing strategy</li>
     *   <li>Return type handling and conversion</li>
     *   <li>Performance optimizations applied by the framework</li>
     *   <li>Expected behavior for edge cases (e.g., empty results)</li>
     * </ul>
     *
     * <p>Common operation types:</p>
     * <ul>
     *   <li>{@link OP#DEFAULT} - Framework determines operation based on SQL and return type (recommended for most cases)</li>
     *   <li>{@link OP#list} - Returns all results as a List</li>
     *   <li>{@link OP#stream} - Returns results as a Stream for lazy processing</li>
     *   <li>{@link OP#findFirst} - Returns the first result wrapped in Optional</li>
     *   <li>{@link OP#findOnlyOne} - Returns exactly one result, throws exception if zero or multiple found</li>
     *   <li>{@link OP#exists} - Returns boolean indicating if any results exist</li>
     *   <li>{@link OP#queryForSingle} - Returns a single scalar value</li>
     *   <li>{@link OP#queryForUnique} - Returns a unique single value or null</li>
     *   <li>{@link OP#update} - Executes UPDATE/INSERT/DELETE and returns row count</li>
     *   <li>{@link OP#largeUpdate} - For updates affecting potentially > Integer.MAX_VALUE rows</li>
     * </ul>
     *
     * <p>Usage examples:</p>
     * <pre>{@code
     * // Existence check
     * @Query(value = "SELECT 1 FROM users WHERE email = :email", op = OP.exists)
     * boolean emailExists(@Bind("email") String email);
     *
     * // Single scalar value
     * @Query(value = "SELECT COUNT(*) FROM users WHERE active = true", op = OP.queryForSingle)
     * long countActiveUsers();
     *
     * // First result from ordered query
     * @Query(value = "SELECT * FROM users ORDER BY created_date DESC", op = OP.findFirst)
     * Optional<User> findLatestUser();
     *
     * // Exactly one result (throws if not exactly one)
     * @Query(value = "SELECT * FROM users WHERE id = :id", op = OP.findOnlyOne)
     * User getUserById(@Bind("id") Long id);
     *
     * // Stream for large result sets
     * @Query(value = "SELECT * FROM large_table", op = OP.stream, fetchSize = 1000)
     * Stream<Record> streamAllRecords();
     *
     * // Explicit update operation
     * @Query(value = "DELETE FROM audit_logs WHERE created_date < :cutoff", op = OP.update)
     * int purgeOldLogs(@Bind("cutoff") Date cutoff);
     * }</pre>
     *
     * <p>When to specify explicitly:</p>
     * <ul>
     *   <li>For existence checks: use {@code OP.exists} for performance</li>
     *   <li>For scalar aggregates: use {@code OP.queryForSingle}</li>
     *   <li>When you need strict validation: use {@code OP.findOnlyOne}</li>
     *   <li>For large result sets: use {@code OP.stream} with appropriate fetch size</li>
     * </ul>
     *
     * <p>Note: In most cases, {@link OP#DEFAULT} is sufficient as the framework intelligently
     * determines the appropriate operation based on the SQL statement type and method return type.</p>
     *
     * @return the operation type, defaults to {@link OP#DEFAULT}
     * @see OP
     */
    OP op() default OP.DEFAULT;

    /**
     * Indicates whether the SQL statement is a stored procedure call.
     * When {@code true}, the framework treats the SQL as a callable statement
     * and handles input/output parameters accordingly.
     *
     * <p>Usage example:</p>
     * <pre>{@code
     * // Stored procedure call with output parameter
     * @Query(value = "{call calculate_bonus(?, ?, ?)}", isProcedure = true)
     * @OutParameter(position = 3, sqlType = Types.DECIMAL)
     * BigDecimal calculateBonus(@Bind("employeeId") long employeeId,
     *                           @Bind("performanceScore") int score);
     * }</pre>
     *
     * <p>When to use:</p>
     * <ul>
     *   <li>Calling stored procedures or functions in the database</li>
     *   <li>When the SQL syntax follows the callable statement format (e.g., {@code {call procedure_name(?, ?)}})</li>
     *   <li>When using output parameters that need to be registered and retrieved</li>
     * </ul>
     *
     * @return {@code true} if the SQL is a stored procedure call; {@code false} (default) otherwise
     * @see OutParameter
     * @see OutParameterList
     */
    boolean isProcedure() default false;

    /**
     * Indicates whether this query should be executed as a batch operation.
     * When {@code true}, the method should accept collection-type parameters and execute the query
     * multiple times with different parameter sets in a single batch for better performance.
     *
     * <p>Batch operations significantly improve performance when executing the same SQL statement
     * multiple times with different parameters by:</p>
     * <ul>
     *   <li>Reducing network round trips to the database</li>
     *   <li>Allowing the database to optimize execution plans</li>
     *   <li>Minimizing parsing and compilation overhead</li>
     *   <li>Enabling better resource utilization</li>
     * </ul>
     *
     * <p>When to use batch operations:</p>
     * <ul>
     *   <li>Inserting multiple records (most common use case)</li>
     *   <li>Updating multiple records with different values</li>
     *   <li>Deleting multiple records based on different criteria</li>
     *   <li>Any scenario where the same SQL runs many times with different parameters</li>
     * </ul>
     *
     * <p>Basic batch insert examples:</p>
     * <pre>{@code
     * // Batch insert with entity list
     * @Query(value = "INSERT INTO users (name, email, status) " +
     *               "VALUES (:name, :email, :status)",
     *        isBatch = true)
     * int[] batchInsertUsers(List<User> users);
     * // Returns array of affected row counts
     *
     * // Batch insert with parallel parameter lists
     * @Query(value = "INSERT INTO products (code, name, price) " +
     *               "VALUES (:code, :name, :price)",
     *        isBatch = true, batchSize = 500)
     * int[] batchInsertProducts(@Bind("code") List<String> codes,
     *                          @Bind("name") List<String> names,
     *                          @Bind("price") List<BigDecimal> prices);
     * }</pre>
     *
     * <p>Batch update examples:</p>
     * <pre>{@code
     * // Batch update with entity list
     * @Query(value = "UPDATE users SET status = :status WHERE id = :id",
     *        isBatch = true)
     * int[] batchUpdateStatus(List<User> users);
     *
     * // Batch update with parallel lists
     * @Query(value = "UPDATE inventory SET quantity = :quantity WHERE product_id = :productId",
     *        isBatch = true)
     * int[] batchUpdateInventory(@Bind("productId") List<Long> productIds,
     *                           @Bind("quantity") List<Integer> quantities);
     * }</pre>
     *
     * <p>Batch delete example:</p>
     * <pre>{@code
     * @Query(value = "DELETE FROM temp_records WHERE id = :id",
     *        isBatch = true)
     * int[] batchDelete(@Bind("id") List<Long> ids);
     * }</pre>
     *
     * <p>Advanced batch examples:</p>
     * <pre>{@code
     * // Large batch with custom batch size
     * @Query(value = "INSERT INTO event_log (timestamp, event_type, data) " +
     *               "VALUES (:timestamp, :eventType, :data)",
     *        isBatch = true, batchSize = 1000)
     * int[] batchLogEvents(List<EventLog> events);
     * // Processes 1000 records per database round trip
     *
     * // Batch with timeout for large operations
     * @Query(value = "INSERT INTO historical_data (date, metric, value) " +
     *               "VALUES (:date, :metric, :value)",
     *        isBatch = true, batchSize = 5000, queryTimeout = 300)
     * int[] importHistoricalData(@Bind("date") List<Date> dates,
     *                           @Bind("metric") List<String> metrics,
     *                           @Bind("value") List<Double> values);
     *
     * // Complex batch operation with multiple fields
     * @Query(value = "INSERT INTO orders (user_id, product_id, quantity, price, order_date) " +
     *               "VALUES (:userId, :productId, :quantity, :price, :orderDate)",
     *        isBatch = true)
     * int[] batchCreateOrders(List<Order> orders);
     * }</pre>
     *
     * <p>Return type requirements:</p>
     * <ul>
     *   <li>{@code int[]} - Array of affected row counts (one per batch item) - most common</li>
     *   <li>{@code void} - No return value needed</li>
     *   <li>{@code int} - Total affected rows across all batches</li>
     * </ul>
     *
     * <p>Parameter requirements:</p>
     * <ul>
     *   <li>At least one parameter must be a {@code Collection} or {@code List}</li>
     *   <li>All collection parameters must have the same size</li>
     *   <li>Framework iterates through collections in parallel, creating one batch item per index</li>
     *   <li>Single (non-collection) parameters are used for all batch items</li>
     * </ul>
     *
     * <p>Parameter combination example:</p>
     * <pre>{@code
     * // Mix of collection and single parameters
     * @Query(value = "INSERT INTO user_actions (user_id, action, category, timestamp) " +
     *               "VALUES (:userId, :action, :category, :timestamp)",
     *        isBatch = true)
     * int[] logUserActions(@Bind("userId") List<Long> userIds,      // varies per batch item
     *                     @Bind("action") List<String> actions,    // varies per batch item
     *                     @Bind("category") String category,       // same for all items
     *                     @Bind("timestamp") Date timestamp);      // same for all items
     * }</pre>
     *
     * <p>Performance considerations:</p>
     * <ul>
     *   <li>Use {@link #batchSize()} to control how many items are sent per database round trip</li>
     *   <li>Larger batch sizes reduce round trips but increase memory usage</li>
     *   <li>Optimal batch size depends on network latency, row size, and database configuration</li>
     *   <li>Consider database transaction log size and timeout limits</li>
     *   <li>Batch operations are typically 10-100× faster than individual operations</li>
     * </ul>
     *
     * <p>Error handling:</p>
     * <ul>
     *   <li>If any batch item fails, the entire batch typically fails (depends on database/driver)</li>
     *   <li>Some drivers support {@code Statement.EXECUTE_FAILED} in the result array</li>
     *   <li>Consider wrapping batch operations in transactions for atomicity</li>
     *   <li>Validate data before batching to minimize mid-batch failures</li>
     * </ul>
     *
     * <p>Best practices:</p>
     * <ul>
     *   <li>Use batch operations for bulk data loading and imports</li>
     *   <li>Set appropriate {@link #batchSize()} based on your data and environment</li>
     *   <li>Use {@link #queryTimeout()} for long-running batch operations</li>
     *   <li>Monitor memory usage with large batches</li>
     *   <li>Consider using transactions to ensure all-or-nothing semantics</li>
     *   <li>Validate collection parameters have matching sizes</li>
     * </ul>
     *
     * @return {@code true} for batch operations; {@code false} (default) for single operations
     * @see #batchSize()
     */
    boolean isBatch() default false;

    /**
     * Indicates whether a single method parameter that is a collection or array should be treated
     * as a single value rather than being expanded for batch operations or IN clauses.
     *
     * <p>Default behavior ({@code isSingleParameter = false}):</p>
     * <ul>
     *   <li>Collections/arrays in IN clauses are expanded: {@code WHERE id IN (:ids)} with {@code List<Long> ids}</li>
     *   <li>For batch operations, collections represent multiple rows to process</li>
     * </ul>
     *
     * <p>When {@code isSingleParameter = true}:</p>
     * <ul>
     *   <li>The collection/array is passed as a single value to the database</li>
     *   <li>Useful for database-native array types (e.g., PostgreSQL arrays)</li>
     *   <li>Useful for JSON array columns</li>
     *   <li>Useful for blob/clob data that happens to be an array</li>
     * </ul>
     *
     * <p>Common use cases:</p>
     * <pre>{@code
     * // PostgreSQL array containment operator
     * @Query(value = "SELECT * FROM products WHERE tags @> :tags",
     *        isSingleParameter = true)
     * List<Product> findByTags(@Bind("tags") String[] tags);
     *
     * // PostgreSQL array equality
     * @Query(value = "SELECT * FROM events WHERE participants = :participants",
     *        isSingleParameter = true)
     * List<Event> findByExactParticipants(@Bind("participants") Long[] participants);
     *
     * // JSON array column
     * @Query(value = "INSERT INTO configs (name, options) VALUES (:name, :options::jsonb)",
     *        isSingleParameter = true)
     * int insertConfig(@Bind("name") String name, @Bind("options") String[] options);
     *
     * // Array intersection
     * @Query(value = "SELECT * FROM items WHERE categories && :categories",
     *        isSingleParameter = true)
     * List<Item> findByCategoryOverlap(@Bind("categories") String[] categories);
     * }</pre>
     *
     * <p>Contrast with default behavior:</p>
     * <pre>{@code
     * // Default: collection is expanded for IN clause
     * @Query(value = "SELECT * FROM users WHERE id IN (:ids)")
     * List<User> findByIds(@Bind("ids") List<Long> ids);
     * // Becomes: SELECT * FROM users WHERE id IN (?, ?, ?, ...)
     *
     * // With isSingleParameter: collection passed as single array value
     * @Query(value = "SELECT * FROM users WHERE id = ANY(:ids)",
     *        isSingleParameter = true)
     * List<User> findByIdsArray(@Bind("ids") Long[] ids);
     * // PostgreSQL: id = ANY($1) where $1 is an array parameter
     * }</pre>
     *
     * <p>Important notes:</p>
     * <ul>
     *   <li>Only applicable when the method has a single collection/array parameter or when specifically needed for one parameter</li>
     *   <li>Database must support the native array or collection type being used</li>
     *   <li>Not commonly needed for standard SQL; primarily for database-specific features</li>
     * </ul>
     *
     * @return {@code true} if collection/array parameters should be treated as single values;
     *         {@code false} (default) for standard expansion behavior
     */
    boolean isSingleParameter() default false;

    /**
     * Indicates whether the SQL statement contains template variables defined by the {@link Define} or {@link DefineList} annotations
     * that will be replaced with query fragments containing named parameters.
     *
     * @return {@code true} if template variables defined by {@link Define} or {@link DefineList} will be replaced with query fragments
     *         containing named parameters; {@code false} otherwise
     * @see Define
     * @see DefineList
     */
    @Beta
    boolean hasDefineWithNamedParameter() default false;

    /**
     * Enables automatic timestamp parameter injection for the query.
     * When {@code true}, a named parameter {@code :now} is automatically set to the current system timestamp
     * without requiring it to be passed as a method parameter.
     *
     * <p>This feature is useful for:</p>
     * <ul>
     *   <li>Audit logging with automatic timestamp capture</li>
     *   <li>Filtering by current time without manual parameter passing</li>
     *   <li>Time-based record selection (e.g., active records, current events)</li>
     *   <li>Consistency in timestamp usage across operations</li>
     *   <li>Reducing boilerplate code for time-related queries</li>
     * </ul>
     *
     * <p><strong>Note:</strong> This feature is marked as {@link Beta} and may evolve in future versions.</p>
     *
     * <p>Basic examples:</p>
     * <pre>{@code
     * // Finding currently active records
     * @Query(value = "SELECT * FROM promotions " +
     *               "WHERE start_date <= :now AND end_date >= :now",
     *        timestamped = true)
     * List<Promotion> findActivePromotions();
     * // :now is automatically set to current timestamp
     *
     * // Audit logging
     * @Query(value = "INSERT INTO audit_log (action, user_id, timestamp) " +
     *               "VALUES (:action, :userId, :now)",
     *        timestamped = true)
     * int logAction(@Bind("action") String action, @Bind("userId") Long userId);
     *
     * // Updating with timestamp
     * @Query(value = "UPDATE users SET last_login = :now WHERE id = :id",
     *        timestamped = true)
     * int updateLastLogin(@Bind("id") Long id);
     * }</pre>
     *
     * <p>Advanced examples:</p>
     * <pre>{@code
     * // Complex time-based filtering
     * @Query(value = "SELECT e.* FROM events e " +
     *               "WHERE e.start_time <= :now " +
     *               "  AND e.end_time >= :now " +
     *               "  AND e.category = :category",
     *        timestamped = true)
     * List<Event> findCurrentEvents(@Bind("category") String category);
     *
     * // Combining with other parameters
     * @Query(value = "SELECT * FROM subscriptions " +
     *               "WHERE user_id = :userId " +
     *               "  AND start_date <= :now " +
     *               "  AND (end_date IS NULL OR end_date >= :now)",
     *        timestamped = true)
     * List<Subscription> findActiveSubscriptions(@Bind("userId") Long userId);
     *
     * // Data archival based on current time
     * @Query(value = "INSERT INTO archive_logs " +
     *               "SELECT *, :now as archived_at FROM logs " +
     *               "WHERE created_date < :cutoffDate",
     *        timestamped = true)
     * int archiveOldLogs(@Bind("cutoffDate") Date cutoffDate);
     *
     * // Scheduled task execution tracking
     * @Query(value = "UPDATE scheduled_tasks " +
     *               "SET last_run = :now, next_run = :now + INTERVAL :intervalMinutes MINUTE " +
     *               "WHERE task_id = :taskId",
     *        timestamped = true)
     * int updateTaskExecution(@Bind("taskId") String taskId,
     *                        @Bind("intervalMinutes") int interval);
     * }</pre>
     *
     * <p>Multiple timestamp usage:</p>
     * <pre>{@code
     * // Using :now multiple times in the same query
     * @Query(value = "INSERT INTO user_sessions (user_id, created_at, last_activity) " +
     *               "VALUES (:userId, :now, :now)",
     *        timestamped = true)
     * int createSession(@Bind("userId") Long userId);
     *
     * // Combining automatic and manual timestamps
     * @Query(value = "SELECT * FROM bookings " +
     *               "WHERE booking_date >= :startDate " +
     *               "  AND booking_date <= :now",
     *        timestamped = true)
     * List<Booking> findBookingsSince(@Bind("startDate") Date startDate);
     * }</pre>
     *
     * <p>Important considerations:</p>
     * <ul>
     *   <li>The {@code :now} parameter is set once when the query is executed, ensuring consistency across the query</li>
     *   <li>The timestamp is obtained from the application server's system time, not the database server</li>
     *   <li>For database server time, use SQL functions like {@code CURRENT_TIMESTAMP} or {@code NOW()} instead</li>
     *   <li>The timestamp format and precision depend on the database column type and JDBC driver</li>
     *   <li>Cannot manually override the {@code :now} parameter when this is enabled</li>
     * </ul>
     *
     * <p>When not to use this feature:</p>
     * <ul>
     *   <li>When you need explicit control over the timestamp value</li>
     *   <li>When you need database server time instead of application time</li>
     *   <li>When the timestamp should be passed in from external sources</li>
     *   <li>When you need different timestamps for different parts of a complex operation</li>
     * </ul>
     *
     * @return {@code true} to automatically inject current timestamp as {@code :now} parameter;
     *         {@code false} (default) for no automatic timestamp injection
     */
    @Beta
    boolean timestamped() default false;

    /**
     * Specifies the query timeout in seconds.
     * If the query execution exceeds this timeout, it will be cancelled and a timeout exception will be thrown.
     *
     * <p>Setting an appropriate timeout is important for:</p>
     * <ul>
     *   <li>Preventing resource exhaustion from long-running queries</li>
     *   <li>Meeting Service Level Agreement (SLA) requirements</li>
     *   <li>Detecting and failing fast on inefficient queries</li>
     *   <li>Protecting the application from database performance issues</li>
     *   <li>Preventing connection pool starvation</li>
     * </ul>
     *
     * <p>Timeout guidelines:</p>
     * <ul>
     *   <li>{@code -1} (default) - Uses the default timeout configured in the connection or DataSource</li>
     *   <li>{@code 0} - No timeout (wait indefinitely - not recommended)</li>
     *   <li>{@code 1-5} seconds - For simple, well-indexed queries that should be very fast</li>
     *   <li>{@code 10-30} seconds - For complex queries with joins or aggregations</li>
     *   <li>{@code 60+} seconds - For batch operations, data migrations, or reporting queries</li>
     * </ul>
     *
     * <p>Usage examples:</p>
     * <pre>{@code
     * // Quick lookup that should complete fast
     * @Query(value = "SELECT * FROM users WHERE id = :id", queryTimeout = 2)
     * User getUserById(@Bind("id") Long id);
     *
     * // Complex reporting query
     * @Query(value = "SELECT ... complex join and aggregation ...", queryTimeout = 60)
     * Report generateMonthlyReport(@Bind("month") int month);
     *
     * // Batch operation with generous timeout
     * @Query(value = "INSERT INTO archive SELECT * FROM data WHERE year = :year",
     *        queryTimeout = 300)
     * int archiveYearData(@Bind("year") int year);
     *
     * // External API call timeout
     * @Query(value = "SELECT get_external_data(:param)", queryTimeout = 10)
     * String callExternalService(@Bind("param") String param);
     * }</pre>
     *
     * <p>Best practices:</p>
     * <ul>
     *   <li>Set timeouts based on expected query performance in production</li>
     *   <li>Consider network latency and database load</li>
     *   <li>Use shorter timeouts for user-facing operations</li>
     *   <li>Log timeout exceptions to identify slow queries</li>
     *   <li>Review and optimize queries that frequently time out</li>
     * </ul>
     *
     * <p>Note: The actual timeout behavior depends on the JDBC driver implementation.
     * Some drivers may not support all timeout values or may have their own minimum/maximum limits.</p>
     *
     * @return the timeout in seconds, or {@code -1} to use the default configured timeout
     */
    int queryTimeout() default -1;

    /**
     * Specifies the JDBC fetch size for the query.
     * This controls how many rows the JDBC driver retrieves from the database in each network round trip.
     *
     * <p>The fetch size is a hint to the JDBC driver about the number of rows that should be fetched
     * from the database when more rows are needed. Setting an appropriate fetch size can significantly
     * improve performance for large result sets by reducing network round trips, but it also affects
     * memory consumption.</p>
     *
     * <p>Fetch size impact:</p>
     * <ul>
     *   <li><strong>Performance:</strong> Larger fetch sizes reduce network round trips but increase memory usage</li>
     *   <li><strong>Memory:</strong> Higher fetch size means more rows buffered in memory</li>
     *   <li><strong>Latency:</strong> Smaller fetch sizes may increase latency for large result sets</li>
     *   <li><strong>Streaming:</strong> Important for {@link java.util.stream.Stream} return types to enable true lazy loading</li>
     * </ul>
     *
     * <p>Fetch size guidelines:</p>
     * <ul>
     *   <li>{@code -1} (default) - Uses the JDBC driver's default fetch size (often 10-50 rows)</li>
     *   <li>{@code 0} - Database-specific behavior; some drivers disable fetch size optimization</li>
     *   <li>{@code 10-100} - Small result sets, interactive queries, or small row sizes</li>
     *   <li>{@code 100-1000} - Medium result sets with moderate row sizes</li>
     *   <li>{@code 1000-10000} - Large result sets, batch processing, or reporting queries</li>
     *   <li>{@code 10000+} - Very large result sets with streaming processing</li>
     * </ul>
     *
     * <p>Basic examples:</p>
     * <pre>{@code
     * // Small lookup query (default is fine)
     * @Query(value = "SELECT * FROM users WHERE id = :id")
     * User getUserById(@Bind("id") Long id);
     *
     * // Large result set with streaming
     * @Query(value = "SELECT * FROM large_table", fetchSize = 1000)
     * Stream<Record> streamLargeTable();
     *
     * // Batch processing with optimal fetch size
     * @Query(value = "SELECT * FROM orders WHERE status = 'PENDING'", fetchSize = 500)
     * List<Order> getPendingOrders();
     * }</pre>
     *
     * <p>Advanced examples:</p>
     * <pre>{@code
     * // Processing millions of records with minimal memory
     * @Query(value = "SELECT * FROM transaction_history WHERE date >= :startDate",
     *        fetchSize = 5000)
     * Stream<Transaction> streamTransactions(@Bind("startDate") Date startDate);
     * // Use with try-with-resources to ensure stream is closed
     *
     * // Large export operation
     * @Query(value = "SELECT * FROM users ORDER BY id", fetchSize = 10000)
     * Stream<User> exportAllUsers();
     *
     * // Memory-constrained environment with small fetch size
     * @Query(value = "SELECT * FROM large_documents", fetchSize = 10)
     * Stream<Document> streamDocuments();  // Smaller batches, more round trips
     *
     * // Balancing memory and performance for reporting
     * @Query(value = "SELECT date, SUM(amount) as total FROM sales " +
     *               "GROUP BY date ORDER BY date", fetchSize = 100)
     * List<DailySales> getDailySalesReport();
     * }</pre>
     *
     * <p>Performance tuning considerations:</p>
     * <pre>{@code
     * // For small, frequent queries - use default or small fetch size
     * @Query(value = "SELECT * FROM products WHERE category = :cat", fetchSize = 50)
     * List<Product> findByCategory(@Bind("cat") String category);
     *
     * // For batch processing - larger fetch size for efficiency
     * @Query(value = "SELECT * FROM orders WHERE status = 'NEW'", fetchSize = 2000)
     * List<Order> getNewOrders();
     *
     * // For streaming large datasets - very large fetch size
     * @Query(value = "SELECT * FROM event_log WHERE date = :date", fetchSize = 10000)
     * Stream<Event> streamDailyEvents(@Bind("date") Date date);
     * }</pre>
     *
     * <p>Database-specific behavior:</p>
     * <ul>
     *   <li><strong>PostgreSQL:</strong> Default fetch size is typically 0 (all rows). Set explicit fetch size for large results</li>
     *   <li><strong>MySQL:</strong> Fetches all rows by default. Use {@code Integer.MIN_VALUE} for row-by-row streaming</li>
     *   <li><strong>Oracle:</strong> Default is 10. Higher values significantly improve performance for large result sets</li>
     *   <li><strong>SQL Server:</strong> Adaptive fetch size based on packet size</li>
     * </ul>
     *
     * <p>Important notes:</p>
     * <ul>
     *   <li>Fetch size is a hint; drivers may ignore or adjust it</li>
     *   <li>Very large fetch sizes can cause OutOfMemoryError if rows are large</li>
     *   <li>Optimal fetch size depends on network latency, row size, and available memory</li>
     *   <li>For {@code Stream} return types, fetch size enables true lazy loading</li>
     *   <li>Profile and test with realistic data to find optimal values</li>
     *   <li>Consider using different fetch sizes for different environments (dev vs. production)</li>
     * </ul>
     *
     * <p>Memory calculation example:</p>
     * <pre>
     * Memory usage ≈ fetchSize × averageRowSize
     * Example: 1000 rows × 2KB/row = 2MB buffered in memory
     * </pre>
     *
     * @return the fetch size hint for the JDBC driver, or {@code -1} to use the driver's default
     */
    int fetchSize() default -1;

    /**
     * Specifies the number of items to process in each database round trip for batch operations.
     * Only applicable when {@link #isBatch()} is {@code true}.
     *
     * <p>The batch size determines how many SQL statements are grouped together and sent to the database
     * in a single batch execution. This is a critical performance tuning parameter that balances:</p>
     * <ul>
     *   <li><strong>Network efficiency:</strong> Larger batches mean fewer round trips</li>
     *   <li><strong>Memory usage:</strong> Larger batches consume more memory</li>
     *   <li><strong>Transaction size:</strong> Larger batches create larger transactions</li>
     *   <li><strong>Error recovery:</strong> Smaller batches may be easier to retry on failure</li>
     * </ul>
     *
     * <p>Batch size selection guidelines:</p>
     * <ul>
     *   <li>{@code 50-100} - Small batches, good for high-frequency operations or limited memory</li>
     *   <li>{@code 100-500} - Medium batches, good default for most use cases (framework default)</li>
     *   <li>{@code 500-1000} - Large batches, good for bulk imports with moderate row sizes</li>
     *   <li>{@code 1000-10000} - Very large batches, for massive data loads with small rows</li>
     * </ul>
     *
     * <p>The default value is {@link JdbcUtil#DEFAULT_BATCH_SIZE}, which is typically optimized for
     * common use cases and provides a good balance between performance and resource usage.</p>
     *
     * <p>Basic examples:</p>
     * <pre>{@code
     * // Using default batch size
     * @Query(value = "INSERT INTO users (name, email) VALUES (:name, :email)",
     *        isBatch = true)
     * int[] insertUsers(List<User> users);
     * // Uses JdbcUtil.DEFAULT_BATCH_SIZE
     *
     * // Small batch size for memory-constrained environment
     * @Query(value = "INSERT INTO large_documents (title, content) VALUES (:title, :content)",
     *        isBatch = true, batchSize = 50)
     * int[] insertDocuments(List<Document> documents);
     * // Processes 50 documents per round trip
     *
     * // Large batch size for bulk import
     * @Query(value = "INSERT INTO event_log (timestamp, type, data) VALUES (:timestamp, :type, :data)",
     *        isBatch = true, batchSize = 5000)
     * int[] importEvents(List<Event> events);
     * // Processes 5000 events per round trip
     * }</pre>
     *
     * <p>Performance tuning examples:</p>
     * <pre>{@code
     * // Optimize for network latency (high latency, use larger batches)
     * @Query(value = "INSERT INTO metrics (name, value, timestamp) VALUES (:name, :value, :timestamp)",
     *        isBatch = true, batchSize = 2000)
     * int[] insertMetrics(List<Metric> metrics);
     *
     * // Optimize for low memory (small row size but many rows)
     * @Query(value = "INSERT INTO simple_logs (timestamp, message) VALUES (:timestamp, :message)",
     *        isBatch = true, batchSize = 10000)
     * int[] insertLogs(List<LogEntry> logs);
     *
     * // Optimize for large rows (documents, blobs)
     * @Query(value = "INSERT INTO files (filename, content) VALUES (:filename, :content)",
     *        isBatch = true, batchSize = 10)
     * int[] insertFiles(List<FileData> files);
     * }</pre>
     *
     * <p>Advanced configuration examples:</p>
     * <pre>{@code
     * // Batch with timeout for very large operations
     * @Query(value = "INSERT INTO archive_data SELECT * FROM staging WHERE batch_id = :batchId",
     *        isBatch = true, batchSize = 1000, queryTimeout = 600)
     * int[] archiveData(@Bind("batchId") List<String> batchIds);
     *
     * // Balance batch size with transaction scope
     * @Transactional
     * @Query(value = "UPDATE inventory SET quantity = quantity - :amount WHERE product_id = :productId",
     *        isBatch = true, batchSize = 500)
     * int[] decrementInventory(@Bind("productId") List<Long> productIds,
     *                         @Bind("amount") List<Integer> amounts);
     * }</pre>
     *
     * <p>How batch size affects execution:</p>
     * <pre>
     * Example: Inserting 10,000 records with batchSize = 500
     * - Total database round trips: 10,000 / 500 = 20 trips
     * - Each trip processes 500 INSERT statements
     * - Memory per trip: ~500 × row size
     *
     * vs. batchSize = 100
     * - Total database round trips: 10,000 / 100 = 100 trips
     * - Each trip processes 100 INSERT statements
     * - Memory per trip: ~100 × row size
     * - More round trips but less memory per trip
     * </pre>
     *
     * <p>Factors to consider when choosing batch size:</p>
     * <ul>
     *   <li><strong>Row size:</strong> Larger rows require smaller batch sizes to avoid memory issues</li>
     *   <li><strong>Network latency:</strong> High latency benefits from larger batches</li>
     *   <li><strong>Database limits:</strong> Some databases have maximum transaction sizes or statement counts</li>
     *   <li><strong>Available memory:</strong> Limited memory requires smaller batches</li>
     *   <li><strong>Concurrent operations:</strong> Consider memory usage across all concurrent operations</li>
     *   <li><strong>Error handling:</strong> Smaller batches may be easier to retry on partial failures</li>
     * </ul>
     *
     * <p>Database-specific considerations:</p>
     * <ul>
     *   <li><strong>PostgreSQL:</strong> Can handle very large batches efficiently; consider 1000-5000</li>
     *   <li><strong>MySQL:</strong> May have max_allowed_packet limit; typically 500-2000</li>
     *   <li><strong>Oracle:</strong> Efficient with batches of 100-1000</li>
     *   <li><strong>SQL Server:</strong> Generally efficient with 500-1000</li>
     * </ul>
     *
     * <p>Memory usage estimation:</p>
     * <pre>
     * Approximate memory per batch = batchSize × averageRowSize × 2
     * (×2 accounts for driver buffering and object overhead)
     *
     * Example calculations:
     * - Small rows (100 bytes): batchSize=5000 → ~1MB per batch
     * - Medium rows (1KB): batchSize=1000 → ~2MB per batch
     * - Large rows (10KB): batchSize=100 → ~2MB per batch
     * - Very large rows (100KB): batchSize=10 → ~2MB per batch
     * </pre>
     *
     * <p>Best practices:</p>
     * <ul>
     *   <li>Start with the default and measure performance</li>
     *   <li>Profile with realistic data volumes and row sizes</li>
     *   <li>Monitor memory usage under load</li>
     *   <li>Consider different values for different environments (dev vs. prod)</li>
     *   <li>Document the rationale for non-default batch sizes</li>
     *   <li>Test with edge cases (very small and very large datasets)</li>
     * </ul>
     *
     * <p>Common anti-patterns to avoid:</p>
     * <ul>
     *   <li>Using batch size of 1 (defeats the purpose of batching)</li>
     *   <li>Using extremely large batch sizes without memory testing</li>
     *   <li>Ignoring database-specific limitations</li>
     *   <li>Not adjusting batch size when row size changes significantly</li>
     * </ul>
     *
     * @return the number of items to process per batch, defaults to {@link JdbcUtil#DEFAULT_BATCH_SIZE}
     * @see #isBatch()
     * @see JdbcUtil#DEFAULT_BATCH_SIZE
     */
    int batchSize() default JdbcUtil.DEFAULT_BATCH_SIZE;
}