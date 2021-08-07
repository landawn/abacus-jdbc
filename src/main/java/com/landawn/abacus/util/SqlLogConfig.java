package com.landawn.abacus.util;

public final class SqlLogConfig {
    boolean isEnabled;
    int maxSqlLogLength;
    long minExecutionTimeForSqlPerfLog;

    SqlLogConfig(final boolean isEnabled, final int maxSqlLogLength) {
        this.isEnabled = isEnabled;
        this.maxSqlLogLength = maxSqlLogLength <= 0 ? JdbcUtil.DEFAULT_MAX_SQL_LOG_LENGTH : maxSqlLogLength;
        this.minExecutionTimeForSqlPerfLog = Long.MAX_VALUE;
    }

    SqlLogConfig(final long minExecutionTimeForSqlPerfLog, final int maxSqlLogLength) {
        this.minExecutionTimeForSqlPerfLog = minExecutionTimeForSqlPerfLog;
        this.maxSqlLogLength = maxSqlLogLength <= 0 ? JdbcUtil.DEFAULT_MAX_SQL_LOG_LENGTH : maxSqlLogLength;
        this.isEnabled = false;
    }

    void set(final boolean isEnabled, final int maxSqlLogLength) {
        this.isEnabled = isEnabled;
        this.maxSqlLogLength = maxSqlLogLength <= 0 ? JdbcUtil.DEFAULT_MAX_SQL_LOG_LENGTH : maxSqlLogLength;
    }

    void set(final long minExecutionTimeForSqlPerfLog, final int maxSqlLogLength) {
        this.minExecutionTimeForSqlPerfLog = minExecutionTimeForSqlPerfLog;
        this.maxSqlLogLength = maxSqlLogLength <= 0 ? JdbcUtil.DEFAULT_MAX_SQL_LOG_LENGTH : maxSqlLogLength;
    }
}