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
 *   <li><b>Performance logging:</b> Logs only SQL statements that exceed a specified execution time threshold,
 *       useful for identifying slow queries in production environments</li>
 * </ul>
 *
 * <p><b>Usage Examples:</b></p>
 * <pre>{@code
 * // Enable general SQL logging with default max length
 * SqlLogConfig config1 = new SqlLogConfig(true, 1000);
 *
 * // Enable performance logging for queries taking more than 500ms
 * // This logs only slow queries, not all queries
 * SqlLogConfig config2 = new SqlLogConfig(500L, 1000);
 *
 * // Enable performance logging with no SQL length limit
 * SqlLogConfig config3 = new SqlLogConfig(100L, -1);
 * }</pre>
 *
 * @see JdbcUtil#enableSqlLog()
 * @see JdbcUtil#setMinExecutionTimeForSqlPerfLog(long)
 */
final class SqlLogConfig {
    boolean isEnabled;
    int maxSqlLogLength;
    long minExecutionTimeForSqlPerfLog;

    /**
     * Constructs a SqlLogConfig for general SQL logging.
     * When enabled, all SQL statements will be logged up to the specified maximum length.
     *
     * @param isEnabled whether SQL logging is enabled
     * @param maxSqlLogLength the maximum length of SQL statements to log (uses default if <= 0)
     */
    SqlLogConfig(final boolean isEnabled, final int maxSqlLogLength) {
        this.isEnabled = isEnabled;
        this.maxSqlLogLength = maxSqlLogLength <= 0 ? JdbcUtil.DEFAULT_MAX_SQL_LOG_LENGTH : maxSqlLogLength;
        minExecutionTimeForSqlPerfLog = Long.MAX_VALUE;
    }

    /**
     * Constructs a SqlLogConfig for performance-based SQL logging.
     * Only SQL statements with execution time exceeding the threshold will be logged.
     *
     * @param minExecutionTimeForSqlPerfLog the minimum execution time in milliseconds for logging
     * @param maxSqlLogLength the maximum length of SQL statements to log (uses default if <= 0)
     */
    SqlLogConfig(final long minExecutionTimeForSqlPerfLog, final int maxSqlLogLength) {
        this.minExecutionTimeForSqlPerfLog = minExecutionTimeForSqlPerfLog;
        this.maxSqlLogLength = maxSqlLogLength <= 0 ? JdbcUtil.DEFAULT_MAX_SQL_LOG_LENGTH : maxSqlLogLength;
        isEnabled = false;
    }

    /**
     * Updates the configuration for general SQL logging.
     * When enabled, all SQL statements will be logged.
     *
     * @param isEnabled whether SQL logging is enabled
     * @param maxSqlLogLength the maximum length of SQL statements to log (uses default if <= 0)
     */
    void set(final boolean isEnabled, final int maxSqlLogLength) {
        this.isEnabled = isEnabled;
        this.maxSqlLogLength = maxSqlLogLength <= 0 ? JdbcUtil.DEFAULT_MAX_SQL_LOG_LENGTH : maxSqlLogLength;
    }

    /**
     * Updates the configuration for performance-based SQL logging.
     * Only SQL statements with execution time exceeding the threshold will be logged.
     *
     * @param minExecutionTimeForSqlPerfLog the minimum execution time in milliseconds for logging
     * @param maxSqlLogLength the maximum length of SQL statements to log (uses default if <= 0)
     */
    void set(final long minExecutionTimeForSqlPerfLog, final int maxSqlLogLength) {
        this.minExecutionTimeForSqlPerfLog = minExecutionTimeForSqlPerfLog;
        this.maxSqlLogLength = maxSqlLogLength <= 0 ? JdbcUtil.DEFAULT_MAX_SQL_LOG_LENGTH : maxSqlLogLength;
    }
}
