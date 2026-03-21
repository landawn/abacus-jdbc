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
 * Enables or disables SQL logging for a DAO method or DAO type.
 *
 * <p>Method-level usage affects only the annotated method. Type-level usage applies to
 * methods whose names match {@link #filter()} by case-insensitive containment or by a full
 * regular-expression match.</p>
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
     * <p>When set to {@code true} (default), SQL statements will be logged.
     * When set to {@code false}, SQL logging is disabled regardless of other settings.</p>
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
     * @return the maximum number of characters to include from SQL statements in logs
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
