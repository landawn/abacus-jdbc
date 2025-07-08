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
import com.landawn.abacus.jdbc.OP;

/**
 * Defines an UPDATE, INSERT, DELETE, or other data modification SQL operation for a DAO method.
 * This annotation allows you to specify SQL data modification statements either inline or by reference
 * to an external SQL mapper, along with various execution parameters including batch processing support.
 * 
 * <p>The annotation supports multiple ways to specify SQL:</p>
 * <ul>
 *   <li>Inline SQL using the {@link #sql()} attribute</li>
 *   <li>Reference to external SQL using the {@link #id()} attribute</li>
 *   <li>Legacy inline SQL using the deprecated {@link #value()} attribute</li>
 * </ul>
 * 
 * <p>Key features:</p>
 * <ul>
 *   <li>Support for single and batch operations</li>
 *   <li>Configurable batch sizes for optimal performance</li>
 *   <li>Query timeout settings</li>
 *   <li>Dynamic SQL with template variables</li>
 *   <li>Automatic timestamping</li>
 * </ul>
 * 
 * <p>Example usage:</p>
 * <pre>{@code
 * public interface UserDao extends CrudDao<User, Long> {
 *     
 *     // Simple update
 *     @Update(sql = "UPDATE users SET email = :email WHERE id = :id")
 *     int updateEmail(@Bind("id") long id, @Bind("email") String email);
 *     
 *     // Batch update with custom batch size
 *     @Update(sql = "UPDATE users SET status = :status WHERE id = :id",
 *             isBatch = true, batchSize = 500)
 *     int[] updateStatusBatch(List<User> users);
 *     
 *     // Insert with auto-timestamp
 *     @Update(sql = "INSERT INTO user_log (user_id, action, timestamp) VALUES (:userId, :action, :now)",
 *             timestamped = true)
 *     int logUserAction(@Bind("userId") long userId, @Bind("action") String action);
 *     
 *     // Delete with timeout
 *     @Update(sql = "DELETE FROM old_records WHERE created_date < :cutoffDate",
 *             queryTimeout = 60)
 *     int deleteOldRecords(@Bind("cutoffDate") Date cutoffDate);
 *     
 *     // Dynamic SQL with Define
 *     @Update(sql = "UPDATE {table} SET status = :status WHERE id = :id",
 *             hasDefineWithNamedParameter = true)
 *     int updateTableStatus(@Define("table") String tableName,
 *                          @Bind("id") long id,
 *                          @Bind("status") String status);
 *     
 *     // Using external SQL reference
 *     @Update(id = "complexUserUpdate")
 *     int performComplexUpdate(@Bind("user") User user);
 *     
 *     // Large update operation
 *     @Update(sql = "DELETE FROM logs WHERE created_date < :date",
 *             op = OP.largeUpdate)
 *     long deleteLargeLogSet(@Bind("date") Date date);
 * }
 * }</pre>
 * 
 * @see Select
 * @see Bind
 * @see Define
 * @see SqlMapper
 * @see <a href="https://stackoverflow.com/questions/1820908/how-to-turn-off-the-eclipse-code-formatter-for-certain-sections-of-java-code">How to turn off the Eclipse code formatter for certain sections of Java code?</a>
 */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.METHOD)
public @interface Update {

    /**
     * Specifies the SQL statement or SQL mapper ID.
     * 
     * @deprecated Use {@link #sql()} for inline SQL statements or {@link #id()} for SQL mapper references.
     * This attribute is maintained for backward compatibility but should not be used in new code.
     * 
     * <p>Example of deprecated usage:</p>
     * <pre>{@code
     * @Update("UPDATE users SET name = :name")  // Deprecated
     * int updateName(@Bind("name") String name);
     * }</pre>
     *
     * @return the SQL statement or mapper ID
     */
    @Deprecated
    String value() default "";

    /**
     * Specifies an inline SQL data modification statement.
     * Use this attribute when you want to define the SQL directly in the annotation.
     * 
     * <p>The SQL can be any DML statement including:</p>
     * <ul>
     *   <li>UPDATE - Modify existing records</li>
     *   <li>INSERT - Add new records</li>
     *   <li>DELETE - Remove records</li>
     *   <li>MERGE/UPSERT - Insert or update based on conditions</li>
     * </ul>
     * 
     * <p>Example:</p>
     * <pre>{@code
     * @Update(sql = "INSERT INTO users (name, email, created_date) " +
     *               "VALUES (:name, :email, CURRENT_TIMESTAMP)")
     * int createUser(@Bind("name") String name, @Bind("email") String email);
     * 
     * @Update(sql = "UPDATE users SET " +
     *               "name = :name, " +
     *               "email = :email, " +
     *               "updated_date = CURRENT_TIMESTAMP " +
     *               "WHERE id = :id")
     * int updateUser(@Bind("id") long id, @Bind("name") String name, @Bind("email") String email);
     * }</pre>
     *
     * @return the SQL data modification statement, or empty string if not used
     */
    String sql() default "";

    /**
     * Specifies the ID of a SQL statement defined in an external SQL mapper.
     * Use this attribute when SQL is defined in XML mapper files referenced by {@link SqlMapper}.
     * 
     * <p>This approach is recommended for:</p>
     * <ul>
     *   <li>Complex multi-line SQL statements</li>
     *   <li>SQL that needs to be shared across multiple methods</li>
     *   <li>Better separation of SQL from Java code</li>
     *   <li>Database-specific SQL variations</li>
     * </ul>
     * 
     * <p>Example:</p>
     * <pre>{@code
     * @Update(id = "mergeUserData")
     * int mergeUser(@Bind("user") User user);
     * 
     * @Update(id = "archiveOldOrders")
     * int archiveOrders(@Bind("cutoffDate") Date cutoffDate);
     * }</pre>
     *
     * @return the SQL mapper ID, or empty string if not used
     */
    String id() default ""; // id defined SqlMapper

    /**
     * Specifies whether this is a batch operation.
     * When true, the method processes multiple records in a single batch for better performance.
     * 
     * <p>Batch operations are beneficial for:</p>
     * <ul>
     *   <li>Bulk inserts of multiple records</li>
     *   <li>Mass updates with different parameters</li>
     *   <li>Multiple deletes in a single transaction</li>
     * </ul>
     * 
     * <p>Requirements for batch operations:</p>
     * <ul>
     *   <li>Method parameter must be a Collection, array, or Iterator</li>
     *   <li>Each element represents parameters for one SQL execution</li>
     *   <li>Return type is typically int[] or long[]</li>
     * </ul>
     * 
     * <p>Example:</p>
     * <pre>{@code
     * @Update(sql = "INSERT INTO users (name, email) VALUES (:name, :email)",
     *         isBatch = true, batchSize = 1000)
     * int[] insertUsers(List<User> users);
     * 
     * @Update(sql = "UPDATE products SET price = :price WHERE id = :id",
     *         isBatch = true)
     * int[] updatePrices(List<PriceUpdate> updates);
     * }</pre>
     *
     * @return {@code true} for batch operations, {@code false} for single operations
     */
    boolean isBatch() default false;

    /**
     * Specifies the batch size for batch operations.
     * This controls how many statements are sent to the database in each batch.
     * 
     * <p>Batch size considerations:</p>
     * <ul>
     *   <li>Larger batches reduce network round trips</li>
     *   <li>Too large batches may cause memory issues</li>
     *   <li>Database-specific limits may apply</li>
     *   <li>Optimal size depends on statement complexity and network latency</li>
     * </ul>
     * 
     * <p>Common batch sizes:</p>
     * <ul>
     *   <li>100-1000: Good for most use cases</li>
     *   <li>50-100: For complex statements or large row data</li>
     *   <li>1000-5000: For simple statements on fast networks</li>
     * </ul>
     * 
     * <p>Example:</p>
     * <pre>{@code
     * // Large batch for simple inserts
     * @Update(sql = "INSERT INTO log_entries (message) VALUES (:message)",
     *         isBatch = true, batchSize = 5000)
     * int[] insertLogs(List<String> messages);
     * 
     * // Smaller batch for complex updates
     * @Update(sql = "UPDATE users SET ... WHERE ...", // complex update
     *         isBatch = true, batchSize = 100)
     * int[] updateUsers(List<User> users);
     * }</pre>
     *
     * @return the batch size, uses {@link JdbcUtil#DEFAULT_BATCH_SIZE} if not specified
     */
    int batchSize() default JdbcUtil.DEFAULT_BATCH_SIZE;

    /**
     * Specifies the query timeout in seconds.
     * If the operation doesn't complete within this time, it will be cancelled and throw an exception.
     * 
     * <p>Timeout is useful for:</p>
     * <ul>
     *   <li>Preventing long-running updates from blocking resources</li>
     *   <li>Protecting against accidental full-table updates</li>
     *   <li>Implementing SLA requirements</li>
     *   <li>Detecting and handling slow database conditions</li>
     * </ul>
     * 
     * <p>Example:</p>
     * <pre>{@code
     * // Quick update with short timeout
     * @Update(sql = "UPDATE cache_table SET value = :value WHERE key = :key",
     *         queryTimeout = 5)
     * int updateCache(@Bind("key") String key, @Bind("value") String value);
     * 
     * // Complex operation with longer timeout
     * @Update(sql = "DELETE FROM archive WHERE date < :cutoff AND size > :maxSize",
     *         queryTimeout = 300)
     * int cleanupArchive(@Bind("cutoff") Date cutoff, @Bind("maxSize") long maxSize);
     * }</pre>
     *
     * @return the timeout in seconds, or -1 for no timeout
     */
    int queryTimeout() default -1;

    /**
     * Indicates whether the method has a single parameter that should be treated as a single value
     * for array/collection database columns.
     * 
     * <p>Set to {@code true} when:</p>
     * <ul>
     *   <li>The method has only one parameter</li>
     *   <li>The parameter is a Collection or array</li>
     *   <li>The target database column is also a Collection/array type</li>
     * </ul>
     * 
     * <p>Example (PostgreSQL array column):</p>
     * <pre>{@code
     * @Update(sql = "UPDATE products SET tags = :tags WHERE id = :id",
     *         isSingleParameter = true)
     * int updateTags(@Bind("id") long id, @Bind("tags") String[] tags);
     * }</pre>
     *
     * @return {@code true} if the collection/array parameter should be treated as a single value
     */
    boolean isSingleParameter() default false;

    /**
     * Indicates whether the SQL contains template variables that need to be replaced using {@link Define} annotations.
     * 
     * <p>Set to {@code true} when using dynamic SQL construction with template variables in curly braces.
     * This enables preprocessing of the SQL to replace {variableName} placeholders.</p>
     * 
     * <p>Example:</p>
     * <pre>{@code
     * @Update(sql = "UPDATE {table} SET {column} = :value WHERE id = :id",
     *         hasDefineWithNamedParameter = true)
     * int updateDynamicColumn(
     *     @Define("table") String table,
     *     @Define("column") String column,
     *     @Bind("value") Object value,
     *     @Bind("id") long id
     * );
     * }</pre>
     *
     * @return {@code true} if the SQL contains Define template variables
     * @see Define
     * @see DefineList
     */
    @Beta
    boolean hasDefineWithNamedParameter() default false;

    /**
     * Automatically adds a timestamp parameter to the query.
     * When set to {@code true}, a named parameter {@code :now} is automatically set to the current system time.
     * 
     * <p>This is useful for:</p>
     * <ul>
     *   <li>Audit trails with consistent timestamps</li>
     *   <li>Setting created/updated timestamps</li>
     *   <li>Time-based operations without manual timestamp passing</li>
     * </ul>
     * 
     * <p>Example:</p>
     * <pre>{@code
     * @Update(sql = "INSERT INTO audit_log (user_id, action, timestamp) " +
     *               "VALUES (:userId, :action, :now)",
     *         timestamped = true)
     * int logAction(@Bind("userId") long userId, @Bind("action") String action);
     * 
     * @Update(sql = "UPDATE users SET last_modified = :now WHERE id = :id",
     *         timestamped = true)
     * int touchUser(@Bind("id") long id);
     * }</pre>
     *
     * @return {@code true} to automatically inject current timestamp as :now parameter
     */
    @Beta
    boolean timestamped() default false;

    /**
     * Specifies the operation type for this update.
     * This determines how the update result is processed and returned.
     * 
     * <p>Available operation types:</p>
     * <ul>
     *   <li>{@link OP#update} (default) - Returns int (affected row count)</li>
     *   <li>{@link OP#largeUpdate} - Returns long for operations affecting many rows</li>
     * </ul>
     * 
     * <p>Use {@link OP#largeUpdate} when:</p>
     * <ul>
     *   <li>The operation might affect more than {@link Integer#MAX_VALUE} rows</li>
     *   <li>Working with very large tables</li>
     *   <li>Database supports returning long row counts</li>
     * </ul>
     * 
     * <p>Example:</p>
     * <pre>{@code
     * // Standard update
     * @Update(sql = "UPDATE users SET active = false WHERE last_login < :date",
     *         op = OP.update)
     * int deactivateInactiveUsers(@Bind("date") Date date);
     * 
     * // Large update for big data operations
     * @Update(sql = "DELETE FROM log_archive WHERE date < :cutoff",
     *         op = OP.largeUpdate)
     * long deleteLargeLogArchive(@Bind("cutoff") Date cutoff);
     * }</pre>
     *
     * @return the operation type for this update
     * @see OP
     */
    OP op() default OP.update;
}