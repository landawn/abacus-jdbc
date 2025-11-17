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

import com.landawn.abacus.jdbc.JdbcUtil;

/**
 * Enables performance logging for SQL operations and DAO methods.
 * This annotation can be applied at both class and method levels to monitor execution times
 * and log performance metrics when operations exceed specified thresholds.
 * 
 * <p>When applied at the class level, it affects all methods in the class based on the filter criteria.
 * When applied at the method level, it only affects that specific method and ignores any filter settings.</p>
 * 
 * <p>Performance logging helps identify slow queries and operations that may need optimization.
 * The logging includes execution time, SQL statements (truncated based on maxSqlLogLength), and method names.</p>
 * 
 * <p><b>Usage Examples:</b></p>
 * <pre>{@code
 * // Class-level: Monitor all methods matching the filter
 * @PerfLog(minExecutionTimeForSql = 500, filter = {"find.*", "search.*"})
 * public interface UserDao extends CrudDao<User, Long> {
 *     List<User> findByName(String name);
 *     User searchByEmail(String email);
 * }
 * 
 * // Method-level: Monitor specific method with custom thresholds
 * public interface OrderDao extends CrudDao<Order, Long> {
 *     @PerfLog(minExecutionTimeForSql = 100, minExecutionTimeForOperation = 500)
 *     List<Order> findLargeOrdersWithDetails(BigDecimal minAmount);
 * }
 * }</pre>
 * 
 * @see SqlLogEnabled
 * @see JdbcUtil
 */
@Retention(RetentionPolicy.RUNTIME)
@Target(value = { ElementType.METHOD, ElementType.TYPE })
public @interface PerfLog {

    /**
     * Specifies the minimum execution time threshold (in milliseconds) for logging SQL performance.
     * SQL statements that execute faster than this threshold will not be logged.
     * 
     * <p>This helps filter out fast queries and focus on potentially problematic slow queries.
     * Set a lower value to capture more queries or a higher value to only log the slowest ones.</p>
     * 
     * <p><b>Usage Examples:</b></p>
     * <pre>{@code
     * @PerfLog(minExecutionTimeForSql = 200) // Log SQLs taking 200ms or more
     * List<User> findActiveUsers();
     * }</pre>
     *
     * @return the minimum execution time in milliseconds for SQL logging
     */
    long minExecutionTimeForSql() default JdbcUtil.DEFAULT_MIN_EXECUTION_TIME_FOR_SQL_PERF_LOG; // 1000

    /**
     * Specifies the maximum length of SQL statements in performance logs.
     * SQL statements longer than this limit will be truncated to prevent excessive log sizes.
     * 
     * <p>This is useful for maintaining readable logs when dealing with complex queries
     * or queries with large parameter lists.</p>
     * 
     * <p><b>Usage Examples:</b></p>
     * <pre>{@code
     * @PerfLog(maxSqlLogLength = 500) // Truncate SQL to 500 characters
     * void executeLargeQuery(String complexQuery);
     * }</pre>
     *
     * @return the maximum number of characters to include from SQL statements in logs
     */
    int maxSqlLogLength() default JdbcUtil.DEFAULT_MAX_SQL_LOG_LENGTH; // 1024

    /**
     * Specifies the minimum execution time threshold (in milliseconds) for logging DAO method performance.
     * DAO operations that complete faster than this threshold will not be logged.
     * 
     * <p>This threshold is typically set higher than minExecutionTimeForSql since DAO operations
     * may include multiple SQL executions, result processing, and business logic.</p>
     * 
     * <p><b>Usage Examples:</b></p>
     * <pre>{@code
     * @PerfLog(minExecutionTimeForOperation = 5000) // Log operations taking 5 seconds or more
     * void processLargeBatchUpdate(List<Order> orders);
     * }</pre>
     * 
     * @return the minimum execution time in milliseconds for DAO operation logging
     */
    long minExecutionTimeForOperation() default JdbcUtil.DEFAULT_MIN_EXECUTION_TIME_FOR_DAO_METHOD_PERF_LOG; // 3000

    /**
     * Specifies filter patterns for methods when the annotation is applied at the class level.
     * Only methods whose names match at least one of these patterns will have performance logging enabled.
     *
     * <p>The patterns support case-insensitive substring matching and regular expressions.
     * Multiple patterns are combined with OR logic.</p>
     *
     * <p>This filter is ignored when the annotation is applied at the method level.</p>
     * 
     * <p><b>Usage Examples:</b></p>
     * <pre>{@code
     * @PerfLog(filter = {"find.*", "search.*", ".*Complex.*"})
     * public interface UserDao {
     *     User findById(Long id);        // Logged (matches "find.*")
     *     List<User> searchByName(String name); // Logged (matches "search.*")
     *     void updateUser(User user);     // Not logged
     *     List<User> executeComplexQuery(); // Logged (matches ".*Complex.*")
     * }
     * }</pre>
     *
     * @return array of filter patterns (default matches all methods)
     */
    String[] filter() default { ".*" };
}