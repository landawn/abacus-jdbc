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

import com.landawn.abacus.annotation.Beta;
import com.landawn.abacus.jdbc.JdbcUtil;

/**
 * Marks a DAO method as a DELETE operation and provides SQL configuration.
 * This annotation enables declarative definition of DELETE statements with support
 * for both single and batch operations.
 * 
 * <p>The SQL can be specified in three ways:</p>
 * <ol>
 *   <li>Inline SQL using the {@code sql} attribute</li>
 *   <li>Reference to SQL defined in SqlMapper using the {@code id} attribute</li>
 *   <li>Legacy inline SQL using the deprecated {@code value} attribute</li>
 * </ol>
 * 
 * <p>Example usage:</p>
 * <pre>{@code
 * public interface UserDao extends CrudDao<User, Long> {
 *     // Simple delete by ID
 *     @Delete(sql = "DELETE FROM users WHERE id = :id")
 *     int deleteById(@Bind("id") Long userId);
 *     
 *     // Delete with multiple conditions
 *     @Delete(sql = "DELETE FROM users WHERE status = :status AND created_date < :date")
 *     int deleteInactiveUsersBefore(@Bind("status") String status, @Bind("date") Date date);
 *     
 *     // Batch delete
 *     @Delete(sql = "DELETE FROM users WHERE id = :id", isBatch = true, batchSize = 100)
 *     int[] deleteByIds(@Bind("id") List<Long> userIds);
 *     
 *     // Using SQL from SqlMapper
 *     @Delete(id = "deleteExpiredUsers")
 *     int deleteExpiredUsers(@Bind("expiryDate") Date expiryDate);
 *     
 *     // With query timeout
 *     @Delete(sql = "DELETE FROM user_sessions WHERE last_active < :date", queryTimeout = 30)
 *     int deleteOldSessions(@Bind("date") Date inactiveDate);
 *     
 *     // With timestamp support
 *     @Delete(sql = "DELETE FROM audit_logs WHERE created_at < :now", timestamped = true)
 *     int deleteOldAuditLogs();  // :now is automatically set to current time
 * }
 * }</pre>
 * 
 * <p>Return types can be:</p>
 * <ul>
 *   <li>{@code int} - number of rows affected</li>
 *   <li>{@code void} - no return value needed</li>
 *   <li>{@code int[]} - for batch operations, affected rows per batch</li>
 * </ul>
 *
 * @see Insert
 * @see Update
 * @see Select
 * @see <a href="https://stackoverflow.com/questions/1820908/how-to-turn-off-the-eclipse-code-formatter-for-certain-sections-of-java-code">How to turn off the Eclipse code formatter for certain sections of Java code?</a>
 * @since 0.8
 */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.METHOD)
public @interface Delete {

    /**
     * Legacy attribute for specifying inline SQL.
     * 
     * @return the SQL DELETE statement
     * @deprecated Use {@code sql="DELETE FROM ..."} or {@code id="deleteById"} for explicit declaration
     */
    @Deprecated
    String value() default "";

    /**
     * Specifies the SQL DELETE statement to execute.
     * Supports named parameters using {@code :paramName} syntax.
     * 
     * <p>Example:</p>
     * <pre>{@code
     * @Delete(sql = "DELETE FROM users WHERE status = :status AND age > :age")
     * int deleteByStatusAndAge(@Bind("status") String status, @Bind("age") int age);
     * }</pre>
     * 
     * <p>You can also use SQL templates with {@link Define} annotations:</p>
     * <pre>{@code
     * @Delete(sql = "DELETE FROM {table} WHERE id = :id", hasDefineWithNamedParameter = true)
     * int deleteFromTable(@Define("table") String tableName, @Bind("id") Long id);
     * }</pre>
     *
     * @return the SQL DELETE statement
     */
    String sql() default "";

    /**
     * Specifies the ID of a SQL statement defined in SqlMapper.
     * This allows centralized SQL management and reuse across multiple DAOs.
     * 
     * <p>Example with SqlMapper:</p>
     * <pre>{@code
     * // In SQL mapper XML or configuration
     * <sql id="deleteInactiveUsers">
     *     DELETE FROM users 
     *     WHERE status = 'INACTIVE' 
     *     AND last_login < :cutoffDate
     * </sql>
     * 
     * // In DAO
     * @Delete(id = "deleteInactiveUsers")
     * int removeInactiveUsers(@Bind("cutoffDate") Date cutoff);
     * }</pre>
     *
     * @return the SQL statement ID from SqlMapper
     */
    String id() default "";

    /**
     * Specifies whether this is a batch delete operation.
     * When {@code true}, the method should accept collection parameters and return {@code int[]}.
     * 
     * <p>Example batch delete:</p>
     * <pre>{@code
     * @Delete(sql = "DELETE FROM orders WHERE id = :id", isBatch = true)
     * int[] deleteOrders(@Bind("id") List<Long> orderIds);
     * 
     * // With custom batch size
     * @Delete(sql = "DELETE FROM logs WHERE id = :id", isBatch = true, batchSize = 500)
     * int[] deleteLogs(@Bind("id") List<Long> logIds);
     * }</pre>
     *
     * @return {@code true} for batch operations, {@code false} for single operations
     */
    boolean isBatch() default false;

    /**
     * Specifies the batch size for batch delete operations.
     * Only applicable when {@link #isBatch()} is {@code true}.
     * 
     * <p>The batch size determines how many records are processed in each database round trip.
     * Larger batch sizes can improve performance but may use more memory.</p>
     * 
     * <p>Example:</p>
     * <pre>{@code
     * // Process 200 deletes per batch
     * @Delete(sql = "DELETE FROM temp_data WHERE id = :id", isBatch = true, batchSize = 200)
     * int[] deleteTempData(@Bind("id") List<Long> ids);
     * }</pre>
     *
     * @return the number of records per batch, defaults to {@link JdbcUtil#DEFAULT_BATCH_SIZE}
     */
    int batchSize() default JdbcUtil.DEFAULT_BATCH_SIZE;

    /**
     * Specifies the query timeout in seconds.
     * If the delete operation takes longer than this timeout, it will be cancelled.
     * 
     * <p>A value of -1 (default) means no specific timeout is set, and the default
     * timeout of the underlying JDBC driver will be used.</p>
     * 
     * <p>Example:</p>
     * <pre>{@code
     * // Allow maximum 60 seconds for this delete operation
     * @Delete(sql = "DELETE FROM large_table WHERE created_date < :date", queryTimeout = 60)
     * int deleteLargeData(@Bind("date") Date cutoffDate);
     * }</pre>
     *
     * @return timeout in seconds, or -1 for default timeout
     */
    int queryTimeout() default -1;

    /**
     * Specifies whether the parameter should be treated as a single value when it's a collection.
     * This is useful when the database column type is an array or when using array operators.
     * 
     * <p>When {@code true}, a collection parameter is passed as a single array value
     * rather than being expanded for an IN clause.</p>
     * 
     * <p>Example with PostgreSQL array:</p>
     * <pre>{@code
     * @Delete(sql = "DELETE FROM events WHERE tags && :tags", isSingleParameter = true)
     * int deleteByTags(@Bind("tags") String[] tags);  // Using array overlap operator
     * }</pre>
     *
     * @return {@code true} to treat collection as single parameter
     */
    boolean isSingleParameter() default false;

    /**
     * Indicates whether the SQL uses {@link Define} or {@link DefineList} annotations
     * for dynamic SQL construction with named parameters.
     * 
     * <p><strong>Note:</strong> This feature is marked as {@code @Beta} and may change.</p>
     * 
     * <p>Example:</p>
     * <pre>{@code
     * @Delete(sql = "DELETE FROM {table} WHERE {condition}", hasDefineWithNamedParameter = true)
     * int dynamicDelete(
     *     @Define("table") String tableName,
     *     @Define("condition") String whereClause
     * );
     * }</pre>
     *
     * @return {@code true} if using Define annotations with named parameters
     * @see Define
     * @see DefineList
     */
    @Beta
    boolean hasDefineWithNamedParameter() default false;

    /**
     * Enables automatic timestamp parameter injection.
     * When {@code true}, the named parameter {@code :now} is automatically set to the current system time.
     * 
     * <p><strong>Note:</strong> This feature is marked as {@code @Beta} and may change.</p>
     * 
     * <p>Example:</p>
     * <pre>{@code
     * @Delete(sql = "DELETE FROM sessions WHERE last_active < :now - INTERVAL '30 minutes'", 
     *         timestamped = true)
     * int deleteInactiveSessions();  // No need to pass current time
     * 
     * @Delete(sql = "DELETE FROM temp_files WHERE created_at < :now", timestamped = true)
     * int deleteOldTempFiles();  // :now is automatically set
     * }</pre>
     *
     * @return {@code true} to auto-inject current timestamp as {@code :now}
     */
    @Beta
    boolean timestamped() default false;
}