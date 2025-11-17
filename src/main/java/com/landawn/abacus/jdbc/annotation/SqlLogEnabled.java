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
 * Controls SQL statement logging for DAO methods or classes.
 * This annotation enables or disables logging of SQL statements during execution,
 * which is useful for debugging, monitoring, and development purposes.
 * 
 * <p>When applied at the class level, it affects all methods in the class based on the filter criteria.
 * When applied at the method level, it only affects that specific method and ignores any filter settings.</p>
 * 
 * <p>SQL logging can help with:</p>
 * <ul>
 *   <li>Debugging query construction and parameter binding</li>
 *   <li>Monitoring database interactions in development</li>
 *   <li>Verifying generated SQL statements</li>
 *   <li>Troubleshooting performance issues</li>
 * </ul>
 * 
 * <p><b>Usage Examples:</b></p>
 * <pre>{@code
 * // Enable logging for entire DAO
 * @SqlLogEnabled
 * public interface UserDao extends CrudDao<User, Long> {
 *     List<User> findByStatus(String status);
 * }
 * 
 * // Selective logging at class level
 * @SqlLogEnabled(filter = {"find.*", "search.*"})
 * public interface OrderDao extends CrudDao<Order, Long> {
 *     List<Order> findByCustomer(Long customerId); // Logged
 *     void updateStatus(Long id, String status);   // Not logged
 * }
 * 
 * // Disable logging for specific method
 * public interface ProductDao extends CrudDao<Product, Long> {
 *     @SqlLogEnabled(false)
 *     List<Product> findSensitiveData();
 *     
 *     @SqlLogEnabled(maxSqlLogLength = 200)
 *     void executeLargeQuery(String query);
 * }
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
     * Specifies filter patterns for methods when the annotation is applied at the class level.
     * Only methods whose names match at least one of these patterns will have SQL logging enabled.
     *
     * <p>The patterns support case-insensitive substring matching and regular expressions.
     * Multiple patterns are combined with OR logic.</p>
     *
     * <p>This filter is ignored when the annotation is applied at the method level.</p>
     * 
     * <p>Common filter patterns:</p>
     * <ul>
     *   <li>{@code "find.*"} - Log all finder methods</li>
     *   <li>{@code ".*ById"} - Log methods ending with "ById"</li>
     *   <li>{@code "update|delete"} - Log update or delete operations</li>
     *   <li>{@code "^(?!insert).*"} - Log everything except methods starting with "insert"</li>
     * </ul>
     * 
     * <p><b>Usage Examples:</b></p>
     * <pre>{@code
     * @SqlLogEnabled(filter = {"find.*", "get.*", "search.*"})
     * public interface UserDao {
     *     User findById(Long id);          // Logged
     *     List<User> searchByName(String name); // Logged
     *     User getByEmail(String email);   // Logged
     *     void updatePassword(Long id);    // Not logged
     * }
     * }</pre>
     *
     * @return array of filter patterns (default matches all methods)
     */
    String[] filter() default { ".*" };
}