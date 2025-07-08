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
 * Marks a DAO method as an INSERT operation and provides SQL configuration.
 * This annotation enables declarative definition of INSERT statements with support
 * for both single and batch operations, as well as auto-generated keys retrieval.
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
 *     // Simple insert
 *     @Insert(sql = "INSERT INTO users (name, email, status) VALUES (:name, :email, :status)")
 *     void insertUser(@Bind("name") String name, @Bind("email") String email, @Bind("status") String status);
 *     
 *     // Insert with generated key retrieval
 *     @Insert(sql = "INSERT INTO users (name, email) VALUES (:user.name, :user.email)")
 *     Long insertAndGetId(@Bind("user") User user);  // Returns generated ID
 *     
 *     // Batch insert
 *     @Insert(sql = "INSERT INTO users (name, email, status) VALUES (:name, :email, :status)", 
 *             isBatch = true, batchSize = 100)
 *     int[] batchInsertUsers(@Bind("name") List<String> names, 
 *                           @Bind("email") List<String> emails, 
 *                           @Bind("status") List<String> statuses);
 *     
 *     // Insert from entity
 *     @Insert(sql = "INSERT INTO users (name, email, created_date) VALUES (:name, :email, :createdDate)")
 *     void insertUser(User user);  // Properties bound automatically
 *     
 *     // Using SQL from SqlMapper
 *     @Insert(id = "insertUserWithProfile")
 *     void insertUserWithProfile(@Bind("user") User user, @Bind("profile") UserProfile profile);
 *     
 *     // With timestamp
 *     @Insert(sql = "INSERT INTO audit_logs (action, user_id, timestamp) VALUES (:action, :userId, :now)", 
 *             timestamped = true)
 *     void logAction(@Bind("action") String action, @Bind("userId") Long userId);
 *     
 *     // Dynamic table name
 *     @Insert(sql = "INSERT INTO {table} (key, value) VALUES (:key, :value)", 
 *             hasDefineWithNamedParameter = true)
 *     void insertIntoTable(@Define("table") String tableName, 
 *                         @Bind("key") String key, 
 *                         @Bind("value") String value);
 * }
 * }</pre>
 * 
 * <p>Return types can be:</p>
 * <ul>
 *   <li>{@code void} - No return value needed</li>
 *   <li>{@code int} - Number of rows affected</li>
 *   <li>{@code Long/Integer} - Auto-generated key (for single insert)</li>
 *   <li>{@code int[]} - For batch operations, affected rows per batch</li>
 *   <li>{@code List<Long>} - Generated keys for batch inserts</li>
 * </ul>
 *
 * @see Update
 * @see Delete
 * @see Select
 * @see <a href="https://stackoverflow.com/questions/1820908/how-to-turn-off-the-eclipse-code-formatter-for-certain-sections-of-java-code">How to turn off the Eclipse code formatter for certain sections of Java code?</a>
 * @since 0.8
 */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.METHOD)
public @interface Insert {

    /**
     * Legacy attribute for specifying inline SQL.
     * 
     * @return the SQL INSERT statement
     * @deprecated Use {@code sql="INSERT INTO ..."} or {@code id="insertById"} for explicit declaration
     */
    @Deprecated
    String value() default "";

    /**
     * Specifies the SQL INSERT statement to execute.
     * Supports named parameters using {@code :paramName} syntax.
     * 
     * <p>Parameter binding examples:</p>
     * <pre>{@code
     * // Binding individual parameters
     * @Insert(sql = "INSERT INTO users (name, email) VALUES (:name, :email)")
     * void insertUser(@Bind("name") String name, @Bind("email") String email);
     * 
     * // Binding from entity properties
     * @Insert(sql = "INSERT INTO products (code, name, price) VALUES (:code, :name, :price)")
     * void insertProduct(Product product);  // Binds product.getCode(), etc.
     * 
     * // Nested property binding
     * @Insert(sql = "INSERT INTO addresses (user_id, street, city) VALUES (:user.id, :address.street, :address.city)")
     * void insertUserAddress(@Bind("user") User user, @Bind("address") Address address);
     * }</pre>
     *
     * @return the SQL INSERT statement
     */
    String sql() default "";

    /**
     * Specifies the ID of an INSERT statement defined in SqlMapper.
     * This allows centralized SQL management and reuse across multiple DAOs.
     * 
     * <p>Example with SqlMapper:</p>
     * <pre>{@code
     * // In SQL mapper XML or configuration
     * <sql id="insertUserWithDefaults">
     *     INSERT INTO users (name, email, status, created_date) 
     *     VALUES (:name, :email, COALESCE(:status, 'ACTIVE'), CURRENT_TIMESTAMP)
     * </sql>
     * 
     * // In DAO
     * @Insert(id = "insertUserWithDefaults")
     * Long createUser(@Bind("name") String name, 
     *                @Bind("email") String email, 
     *                @Bind("status") String status);
     * }</pre>
     *
     * @return the SQL statement ID from SqlMapper
     */
    String id() default "";

    /**
     * Specifies whether this is a batch insert operation.
     * When {@code true}, the method should accept collection parameters and return {@code int[]}.
     * 
     * <p>Batch inserts significantly improve performance when inserting multiple records
     * by reducing database round trips.</p>
     * 
     * <p>Example batch insert:</p>
     * <pre>{@code
     * // Collection of entities
     * @Insert(sql = "INSERT INTO users (name, email) VALUES (:name, :email)", isBatch = true)
     * int[] batchInsertUsers(List<User> users);
     * 
     * // Parallel lists
     * @Insert(sql = "INSERT INTO products (code, name, price) VALUES (:code, :name, :price)", 
     *         isBatch = true, batchSize = 500)
     * int[] batchInsertProducts(@Bind("code") List<String> codes,
     *                          @Bind("name") List<String> names,
     *                          @Bind("price") List<BigDecimal> prices);
     * }</pre>
     *
     * @return {@code true} for batch operations, {@code false} for single operations
     */
    boolean isBatch() default false;

    /**
     * Specifies the batch size for batch insert operations.
     * Only applicable when {@link #isBatch()} is {@code true}.
     * 
     * <p>The batch size determines how many records are sent to the database in each batch.
     * Optimal batch size depends on:</p>
     * <ul>
     *   <li>Database capabilities and configuration</li>
     *   <li>Network latency</li>
     *   <li>Record size</li>
     *   <li>Available memory</li>
     * </ul>
     * 
     * <p>Example:</p>
     * <pre>{@code
     * // Process 1000 records per batch for large datasets
     * @Insert(sql = "INSERT INTO log_entries (timestamp, message) VALUES (:timestamp, :message)", 
     *         isBatch = true, batchSize = 1000)
     * int[] insertLogs(List<LogEntry> entries);
     * }</pre>
     *
     * @return the number of records per batch, defaults to {@link JdbcUtil#DEFAULT_BATCH_SIZE}
     */
    int batchSize() default JdbcUtil.DEFAULT_BATCH_SIZE;

    /**
     * Specifies the query timeout in seconds.
     * If the insert operation takes longer than this timeout, it will be cancelled.
     * 
     * <p>A value of -1 (default) means no specific timeout is set.
     * Useful for large batch inserts or complex insert operations.</p>
     * 
     * <p>Example:</p>
     * <pre>{@code
     * // Allow up to 5 minutes for large batch insert
     * @Insert(sql = "INSERT INTO historical_data (date, value) VALUES (:date, :value)", 
     *         isBatch = true, queryTimeout = 300)
     * int[] importHistoricalData(List<HistoricalRecord> records);
     * }</pre>
     *
     * @return timeout in seconds, or -1 for default timeout
     */
    int queryTimeout() default -1;

    /**
     * Specifies whether the parameter should be treated as a single value when it's a collection.
     * This is useful when inserting array or JSON types into database columns.
     * 
     * <p>When {@code true}, a collection parameter is passed as a single value
     * rather than being used for batch operations.</p>
     * 
     * <p>Example with PostgreSQL array:</p>
     * <pre>{@code
     * @Insert(sql = "INSERT INTO events (name, tags) VALUES (:name, :tags)", 
     *         isSingleParameter = true)
     * void insertEvent(@Bind("name") String name, @Bind("tags") String[] tags);
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
     * <p>Example with dynamic table names:</p>
     * <pre>{@code
     * @Insert(sql = "INSERT INTO {table} ({columns}) VALUES ({values})", 
     *         hasDefineWithNamedParameter = true)
     * void dynamicInsert(@Define("table") String tableName,
     *                   @Define("columns") String columnList,
     *                   @Define("values") String valueList);
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
     * <p>This is particularly useful for audit trails and timestamp tracking:</p>
     * <pre>{@code
     * @Insert(sql = "INSERT INTO user_actions (user_id, action, timestamp) VALUES (:userId, :action, :now)", 
     *         timestamped = true)
     * void recordUserAction(@Bind("userId") Long userId, @Bind("action") String action);
     * 
     * @Insert(sql = "INSERT INTO data_imports (file_name, imported_at) VALUES (:fileName, :now)", 
     *         timestamped = true)
     * void recordImport(@Bind("fileName") String fileName);
     * }</pre>
     *
     * @return {@code true} to auto-inject current timestamp as {@code :now}
     */
    @Beta
    boolean timestamped() default false;
}