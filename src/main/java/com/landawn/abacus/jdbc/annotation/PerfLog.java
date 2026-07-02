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
 * Enables SQL and DAO-method performance logging for the annotated method (or for the DAO type,
 * with optional {@link #filter()} matching).
 *
 * <p>The DAO proxy reads this annotation at build time ({@code DaoImpl}). For each invocation,
 * the proxy snapshots {@code System.currentTimeMillis()} before and after the call and emits a
 * log entry only when:</p>
 * <ul>
 *   <li>The SQL portion of the call exceeded {@link #minExecutionTimeForSql()} milliseconds — the
 *       statement text (capped at {@link #maxSqlLogLength()} characters) is logged at INFO level
 *       (as a {@code [SQL-PERF]} entry on the SQL logger).</li>
 *   <li>The whole DAO-method invocation exceeded {@link #minExecutionTimeForOperation()} ms —
 *       the method name and total elapsed time are logged.</li>
 * </ul>
 *
 * <p><b>Lookup precedence:</b> a method-level {@code @PerfLog} overrides a type-level one. When
 * neither is present, the global defaults configured on {@link JdbcUtil} are used.</p>
 *
 * <p><b>Filter semantics (type-level only):</b> each entry matches when it is contained in the
 * method name (case-insensitive) or matches the full method name as a regular expression. The
 * filter is ignored entirely when the annotation is placed on a single method.</p>
 *
 * <p><b>Usage Examples:</b></p>
 * <pre>{@code
 * // Type-level: every method in the DAO is monitored, with a relaxed 5-second budget.
 * @PerfLog(minExecutionTimeForSql = 1000,
 *          minExecutionTimeForOperation = 5000)
 * public interface UserDao extends CrudDao<User, Long, UserDao> {
 *
 *     // Method-level override: this hot path uses a tighter threshold and shorter logs.
 *     @PerfLog(minExecutionTimeForSql = 100,
 *              maxSqlLogLength = 200)
 *     @Query("SELECT * FROM users WHERE id = :id")
 *     User findById(@Bind("id") Long id) throws SQLException;
 *
 *     // Type-level @PerfLog also applies here, with the defaults from the type.
 *     @Query("SELECT COUNT(*) FROM users")
 *     long countAll() throws SQLException;
 * }
 *
 * // Filter only the read paths at the type level.
 * @PerfLog(filter = { "find", "get", "query", "list", "count" })
 * public interface ReportDao { ... }
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
     * @return the minimum execution time in milliseconds for SQL logging; defaults to
     *         {@link JdbcUtil#DEFAULT_MIN_EXECUTION_TIME_FOR_SQL_PERF_LOG} (1000 ms)
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
     * @return the maximum number of characters to include from SQL statements in logs; defaults to
     *         {@link JdbcUtil#DEFAULT_MAX_SQL_LOG_LENGTH} (1024 characters)
     */
    int maxSqlLogLength() default JdbcUtil.DEFAULT_MAX_SQL_LOG_LENGTH; // 1024

    /**
     * Specifies the minimum execution time threshold (in milliseconds) for logging DAO method performance.
     * DAO operations that complete faster than this threshold will not be logged.
     *
     * <p>This threshold is typically set higher than {@link #minExecutionTimeForSql()} since DAO operations
     * may include multiple SQL executions, result processing, and business logic.</p>
     *
     * <p><b>Usage Examples:</b></p>
     * <pre>{@code
     * @PerfLog(minExecutionTimeForOperation = 5000) // Log operations taking 5 seconds or more
     * void processLargeBatchUpdate(List<Order> orders);
     * }</pre>
     *
     * @return the minimum execution time in milliseconds for DAO operation logging; defaults to
     *         {@link JdbcUtil#DEFAULT_MIN_EXECUTION_TIME_FOR_DAO_METHOD_PERF_LOG} (3000 ms)
     */
    long minExecutionTimeForOperation() default JdbcUtil.DEFAULT_MIN_EXECUTION_TIME_FOR_DAO_METHOD_PERF_LOG; // 3000

    /**
     * Specifies the type-level method-name filter.
     *
     * <p>Each entry matches when it is contained in the method name ignoring case, or when
     * it matches the full method name as a regular expression. This filter is ignored for
     * method-level usage.</p>
     *
     * @return array of filter patterns (default matches all methods)
     */
    String[] filter() default { ".*" };
}
