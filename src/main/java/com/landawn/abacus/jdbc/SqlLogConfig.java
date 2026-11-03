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
 * <p>Instances are immutable. {@link JdbcUtil} keeps a separate per-thread instance for each mode, replaced
 * by a new one whenever the thread's settings change, and reads only that mode's fields from it. A thread at
 * the default settings holds no instance and reads a default instance shared by every thread.</p>
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
    // WHY final fields and no setters: JdbcUtil shares its default instances across all threads (see
    // JdbcUtil.sqlLogConfig()), so mutating one would change the logging of every thread at the defaults.

    /**
     * The smallest usable value for {@link #maxSqlLogLength}: {@code Strings.abbreviate} rejects any
     * maximum width shorter than its {@code "..."} marker plus one character, so a configured value of
     * 1-3 would make the log-truncation call itself throw {@code IllegalArgumentException} while a
     * statement is being prepared or executed.
     */
    static final int MIN_MAX_SQL_LOG_LENGTH = 4;

    /** {@code true} if general SQL logging (logging every executed statement) is enabled. */
    final boolean isEnabled;

    /** The maximum length, in characters, of a SQL statement that will be logged; longer statements are truncated. */
    final int maxSqlLogLength;

    /**
     * The minimum execution time, in milliseconds, for performance logging. A statement is logged only when its
     * elapsed execution time is greater than or equal to this value. {@code Long.MAX_VALUE} effectively disables
     * performance logging, and a negative value also disables it.
     */
    final long sqlPerfLogThresholdMillis;

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
     * @param isEnabled {@code true} to enable SQL logging for all statements, {@code false} to disable.
     * @param maxSqlLogLength the maximum length of SQL statements to log. If {@code <= 0},
     *                        {@link JdbcUtil#DEFAULT_MAX_SQL_LOG_LENGTH} is used; values of 1-3 are raised
     *                        to 4, the smallest length the truncation marker supports.
     */
    SqlLogConfig(final boolean isEnabled, final int maxSqlLogLength) {
        this.isEnabled = isEnabled;
        this.maxSqlLogLength = normalizeMaxSqlLogLength(maxSqlLogLength);
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
     *                        {@link JdbcUtil#DEFAULT_MAX_SQL_LOG_LENGTH} is used; values of 1-3 are raised
     *                        to 4, the smallest length the truncation marker supports.
     */
    SqlLogConfig(final long sqlPerfLogThresholdMillis, final int maxSqlLogLength) {
        this.sqlPerfLogThresholdMillis = sqlPerfLogThresholdMillis;
        this.maxSqlLogLength = normalizeMaxSqlLogLength(maxSqlLogLength);
        isEnabled = false;
    }

    /**
     * Maps a caller-supplied maximum SQL log length onto the usable range: non-positive values fall back
     * to {@link JdbcUtil#DEFAULT_MAX_SQL_LOG_LENGTH}, and values of 1-3 are raised to
     * {@link #MIN_MAX_SQL_LOG_LENGTH} so log truncation can never throw while a statement is being
     * prepared or executed (see {@code JdbcUtil.logSql}/{@code JdbcUtil.handleSqlLog}, the latter of
     * which runs in a {@code finally} block and must never mask the statement's own outcome).
     */
    private static int normalizeMaxSqlLogLength(final int maxSqlLogLength) {
        return maxSqlLogLength <= 0 ? JdbcUtil.DEFAULT_MAX_SQL_LOG_LENGTH : Math.max(maxSqlLogLength, MIN_MAX_SQL_LOG_LENGTH);
    }
}
