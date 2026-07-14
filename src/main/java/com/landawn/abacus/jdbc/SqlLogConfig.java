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
package com.landawn.abacus.jdbc;

/**
 * Configuration class for SQL logging behavior in the JDBC framework.
 * This class manages settings for SQL statement logging and performance logging thresholds.
 *
 * <p>The configuration supports two modes:</p>
 * <ul>
 *   <li><b>General SQL logging:</b> Logs all SQL statements when enabled, useful for debugging</li>
 *   <li><b>Performance logging:</b> Logs only SQL statements whose execution time reaches or exceeds a specified threshold,
 *       useful for identifying slow queries in production environments</li>
 * </ul>
 *
 * <p><b>Usage Examples:</b></p>
 * <pre>{@code
 * // Enable general SQL logging with a 1000-char limit
 * SqlLogConfig config1 = new SqlLogConfig(true, 1000);
 *
 * // Enable performance logging for queries taking 500ms or more
 * // This logs only slow queries, not all queries
 * SqlLogConfig config2 = new SqlLogConfig(500L, 1000);
 *
 * // Enable performance logging; a non-positive max length falls back to the default
 * SqlLogConfig config3 = new SqlLogConfig(100L, -1);
 * }</pre>
 *
 * @see JdbcUtil#enableSqlLog()
 * @see JdbcUtil#setSqlPerfLogThresholdMillis(long)
 */
final class SqlLogConfig {
    // Note: instances are strictly thread-confined (held in ThreadLocals in JdbcUtil and only read/written
    // by the owning thread), so the fields deliberately are NOT volatile.

    /** {@code true} if general SQL logging (logging every executed statement) is enabled. */
    boolean isEnabled;

    /** The maximum length, in characters, of a SQL statement that will be logged; longer statements are truncated. */
    int maxSqlLogLength;

    /**
     * The minimum execution time, in milliseconds, for performance logging. A statement is logged only when its
     * elapsed execution time is greater than or equal to this value. {@code Long.MAX_VALUE} effectively disables
     * performance logging, and a negative value also disables it.
     */
    long sqlPerfLogThresholdMillis;

    /**
     * Constructs a SqlLogConfig for general SQL logging.
     * When enabled, all SQL statements will be logged up to the specified maximum length.
     *
     * <p>This constructor creates a configuration that logs all SQL statements regardless of
     * execution time. The performance logging threshold is set to {@code Long.MAX_VALUE},
     * effectively disabling performance-based logging.</p>
     *
     * <p><b>Usage Examples:</b></p>
     * <pre>{@code
     * // Enable SQL logging with a 1000-char limit
     * SqlLogConfig config = new SqlLogConfig(true, 1000);
     *
     * // Passing 0 or a negative value falls back to the default max length
     * SqlLogConfig config2 = new SqlLogConfig(true, 0);
     * }</pre>
     *
     * @param isEnabled {@code true} to enable SQL logging for all statements, {@code false} to disable
     * @param maxSqlLogLength the maximum length of SQL statements to log. If {@code <= 0},
     *                        {@link JdbcUtil#DEFAULT_MAX_SQL_LOG_LENGTH} is used.
     */
    SqlLogConfig(final boolean isEnabled, final int maxSqlLogLength) {
        this.isEnabled = isEnabled;
        this.maxSqlLogLength = maxSqlLogLength <= 0 ? JdbcUtil.DEFAULT_MAX_SQL_LOG_LENGTH : maxSqlLogLength;
        sqlPerfLogThresholdMillis = Long.MAX_VALUE;
    }

    /**
     * Constructs a SqlLogConfig for performance-based SQL logging.
     * Only SQL statements whose execution time reaches or exceeds the threshold will be logged.
     *
     * <p>This constructor creates a configuration that logs only slow queries, making it suitable
     * for production environments where you want to identify performance bottlenecks without
     * logging all SQL statements. The general SQL logging flag is set to {@code false}.</p>
     *
     * <p><b>Usage Examples:</b></p>
     * <pre>{@code
     * // Log only queries taking 500ms or more
     * SqlLogConfig config = new SqlLogConfig(500L, 1000);
     *
     * // Log queries taking 1 second or more with the default SQL length
     * SqlLogConfig config2 = new SqlLogConfig(1000L, 0);
     *
     * // Log all queries (set threshold to 0)
     * SqlLogConfig config3 = new SqlLogConfig(0L, 1000);
     * }</pre>
     *
     * @param sqlPerfLogThresholdMillis the minimum execution time in milliseconds for logging.
     *                                      SQL statements whose execution time is greater than or equal to this
     *                                      threshold will be logged. Set to {@code 0} to log every executed query
     *                                      based on this mechanism; pass a negative value to disable performance
     *                                      logging entirely.
     * @param maxSqlLogLength the maximum length of SQL statements to log. If {@code <= 0},
     *                        {@link JdbcUtil#DEFAULT_MAX_SQL_LOG_LENGTH} is used.
     */
    SqlLogConfig(final long sqlPerfLogThresholdMillis, final int maxSqlLogLength) {
        this.sqlPerfLogThresholdMillis = sqlPerfLogThresholdMillis;
        this.maxSqlLogLength = maxSqlLogLength <= 0 ? JdbcUtil.DEFAULT_MAX_SQL_LOG_LENGTH : maxSqlLogLength;
        isEnabled = false;
    }

    /**
     * Updates the configuration for general SQL logging.
     * When enabled, all SQL statements will be logged regardless of execution time.
     *
     * <p>This method switches the configuration to general SQL logging mode,
     * where all statements are logged. The performance-based logging threshold is
     * reset to {@code Long.MAX_VALUE} (effectively disabled).</p>
     *
     * <p><b>Usage Examples:</b></p>
     * <pre>{@code
     * SqlLogConfig config = new SqlLogConfig(false, 1000);
     *
     * // Later, enable general SQL logging
     * config.set(true, 2000);
     *
     * // Disable SQL logging
     * config.set(false, 1000);
     * }</pre>
     *
     * @param isEnabled {@code true} to enable SQL logging for all statements, {@code false} to disable
     * @param maxSqlLogLength the maximum length of SQL statements to log. If {@code <= 0},
     *                        {@link JdbcUtil#DEFAULT_MAX_SQL_LOG_LENGTH} is used.
     */
    void set(final boolean isEnabled, final int maxSqlLogLength) {
        this.isEnabled = isEnabled;
        this.maxSqlLogLength = maxSqlLogLength <= 0 ? JdbcUtil.DEFAULT_MAX_SQL_LOG_LENGTH : maxSqlLogLength;
        this.sqlPerfLogThresholdMillis = Long.MAX_VALUE;
    }

    /**
     * Updates the configuration for performance-based SQL logging.
     * Only SQL statements whose execution time reaches or exceeds the threshold will be logged.
     *
     * <p><b>Usage Examples:</b></p>
     * <pre>{@code
     * SqlLogConfig config = new SqlLogConfig(true, 1000);
     *
     * // Switch to performance-based logging for queries over 500ms
     * config.set(500L, 2000);
     *
     * // Change threshold to 1 second
     * config.set(1000L, 2000);
     *
     * // Log all queries based on execution time (threshold = 0)
     * config.set(0L, 2000);
     * }</pre>
     *
     * @param sqlPerfLogThresholdMillis the minimum execution time in milliseconds for logging.
     *                                      SQL statements whose execution time is greater than or equal to this
     *                                      threshold will be logged. Set to {@code 0} to log every executed query
     *                                      based on this mechanism; pass a negative value to disable performance
     *                                      logging entirely.
     * @param maxSqlLogLength the maximum length of SQL statements to log. If {@code <= 0},
     *                        {@link JdbcUtil#DEFAULT_MAX_SQL_LOG_LENGTH} is used.
     */
    void set(final long sqlPerfLogThresholdMillis, final int maxSqlLogLength) {
        this.isEnabled = false;
        this.sqlPerfLogThresholdMillis = sqlPerfLogThresholdMillis;
        this.maxSqlLogLength = maxSqlLogLength <= 0 ? JdbcUtil.DEFAULT_MAX_SQL_LOG_LENGTH : maxSqlLogLength;
    }
}
