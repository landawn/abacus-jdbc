/*
 * Copyright (c) 2021, Haiyang Li.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.landawn.abacus.jdbc;

final class SqlLogConfig {
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