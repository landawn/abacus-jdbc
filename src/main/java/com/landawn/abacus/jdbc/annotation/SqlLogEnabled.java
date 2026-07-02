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
 * Toggles whether SQL statements are logged for a DAO method (or all matching methods on a DAO
 * type). When enabled, every prepared statement executed through the DAO proxy is written to the
 * SQL logger, truncated to {@link #maxSqlLogLength()} characters.
 *
 * <p>The DAO proxy reads this annotation at proxy-build time ({@code DaoImpl}). A method-level
 * {@code @SqlLogEnabled} wins over a type-level one. When neither is present, logging follows
 * the global default configured on {@link JdbcUtil}.</p>
 *
 * <p><b>Filter semantics (type-level only):</b> each {@link #filter()} entry matches when it is
 * contained in the method name (case-insensitive) or matches the full method name as a regular
 * expression. The filter is ignored entirely for method-level usage. Typical patterns:</p>
 * <ul>
 *   <li>{@code ".*"} (default) — every method.</li>
 *   <li>{@code "insert", "update", "delete"} — only write methods.</li>
 *   <li>{@code "find.*"} — methods whose name starts with {@code find}.</li>
 * </ul>
 *
 * <p><b>Usage Examples:</b></p>
 * <pre>{@code
 * // Method-level: hide a query that prints sensitive payment data.
 * public interface PaymentDao extends CrudDao<Payment, Long, PaymentDao> {
 *     @SqlLogEnabled(false)
 *     @Query("SELECT * FROM payment_card WHERE id = :id")
 *     Payment findById(@Bind("id") Long id) throws SQLException;
 *
 *     @Query("UPDATE payment_card SET status = :status WHERE id = :id")
 *     int updateStatus(@Bind("id") Long id, @Bind("status") String status) throws SQLException;
 * }
 *
 * // Type-level: log only the write methods, truncated to 2 KB.
 * @SqlLogEnabled(value = true, maxSqlLogLength = 2048,
 *                filter = { "insert", "update", "delete", "save", "remove" })
 * public interface UserDao extends CrudDao<User, Long, UserDao> { ... }
 * }</pre>
 *
 * @see PerfLog
 * @see JdbcUtil
 */
@Retention(RetentionPolicy.RUNTIME)
@Target(value = { ElementType.METHOD, ElementType.TYPE })
public @interface SqlLogEnabled {

    /**
     * Specifies whether SQL logging is enabled or disabled.
     *
     * <p>When set to {@code true}, SQL statements will be logged for the annotated scope; when set to
     * {@code false}, SQL logging is disabled for that scope. This element defaults to {@code true},
     * meaning a bare {@code @SqlLogEnabled} turns logging on for the scope it is placed on &mdash; it
     * does <em>not</em> imply SQL logging is globally enabled by default. When no {@code @SqlLogEnabled}
     * is present at all, logging follows the global default configured on {@link JdbcUtil}. A method-level
     * {@code @SqlLogEnabled} still takes precedence over a type-level one (see the type-level
     * documentation).</p>
     *
     * <p>This is useful for:</p>
     * <ul>
     *   <li>Temporarily disabling logging for specific methods</li>
     *   <li>Excluding sensitive queries from logs</li>
     *   <li>Reducing log volume for high-frequency operations</li>
     * </ul>
     *
     * <p><b>Usage Examples:</b></p>
     * <pre>{@code
     * @SqlLogEnabled(true)  // Explicitly enable logging
     * List<User> findAll();
     *
     * @SqlLogEnabled(false) // Disable logging for sensitive data
     * List<PaymentInfo> findPaymentDetails();
     * }</pre>
     *
     * @return {@code true} to enable SQL logging, {@code false} to disable it
     */
    boolean value() default true;

    /**
     * Specifies the maximum length of SQL statements in logs.
     * SQL statements longer than this limit will be truncated to prevent excessive log sizes.
     * This setting only has an effect when SQL logging is enabled via {@link #value()}.
     *
     * <p>This is particularly useful when dealing with:</p>
     * <ul>
     *   <li>Large INSERT statements with many values</li>
     *   <li>Complex queries with multiple joins</li>
     *   <li>Queries with large IN clauses</li>
     *   <li>Statements with embedded large text or binary data</li>
     * </ul>
     *
     * <p><b>Usage Examples:</b></p>
     * <pre>{@code
     * @SqlLogEnabled(maxSqlLogLength = 500)
     * void insertBatchData(List<Data> largeDataset);
     *
     * @SqlLogEnabled(maxSqlLogLength = 2048) // Allow longer logs for complex queries
     * List<Report> generateComplexReport();
     * }</pre>
     *
     * @return the maximum number of characters to include from SQL statements in logs; defaults to
     *         {@link JdbcUtil#DEFAULT_MAX_SQL_LOG_LENGTH} (1024)
     */
    int maxSqlLogLength() default JdbcUtil.DEFAULT_MAX_SQL_LOG_LENGTH; // 1024

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
