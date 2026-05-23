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

import com.landawn.abacus.jdbc.dao.CrudDao;

/**
 * Provides DAO-level configuration options that affect query generation and execution behavior.
 * This annotation allows fine-tuning of various framework behaviors at the DAO interface level,
 * overriding global defaults for specific use cases.
 * 
 * <p>Configuration options include:</p>
 * <ul>
 *   <li>Automatic LIMIT clause addition for single-result queries</li>
 *   <li>ID generation behavior for insert operations</li>
 *   <li>Join condition handling with {@code null} values</li>
 *   <li>Column fetching strategies for DataSet queries</li>
 * </ul>
 * 
 * <p><b>Usage Examples:</b></p>
 * <pre>{@code
 * @DaoConfig(
 *     addLimitForSingleQuery = true,
 *     callGenerateIdForInsertIfIdNotSet = true
 * )
 * public interface UserDao extends CrudDao<User, Long, SqlBuilder.PSC, UserDao> {
 *     // Single query methods will automatically add LIMIT 1
 *     @Query("SELECT * FROM users WHERE email = :email")
 *     User findByEmail(@Bind("email") String email);   // LIMIT 1 added automatically
 *
 *     // ID generation will be called if user.id is null or 0
 *     default User createUser(String name, String email) {
 *         User user = new User(name, email);
 *         insert(user);   // generateId() called automatically if needed
 *         return user;
 *     }
 * }
 * 
 * @DaoConfig(allowJoiningByNullOrDefaultValue = true)
 * public interface OrderDao extends CrudDao<Order, Long> {
 *     // Framework-managed @JoinedBy joins are allowed even when the join key is null
 *     List<Order> listAllWithItems(Collection<String> selectPropNames);
 * }
 * }</pre>
 *
 * @see CrudDao
 */
@Retention(RetentionPolicy.RUNTIME)
@Target(value = { ElementType.TYPE })
public @interface DaoConfig {

    /**
     * Controls whether to automatically add a LIMIT clause (or its database equivalent) to
     * single-result {@link com.landawn.abacus.query.condition.Condition Condition}-based query
     * methods declared by {@code Dao}/{@code CrudDao}.
     *
     * <p>When {@code true}, the framework appends:</p>
     * <ul>
     *   <li>{@code LIMIT 1} for {@code exists(Condition)} and {@code findFirst(...)} variants</li>
     *   <li>{@code LIMIT 2} for {@code findOnlyOne(...)} variants (so duplicates can still be detected)</li>
     * </ul>
     *
     * <p>The LIMIT is <em>not</em> added for {@code count(Condition)} (which already issues a
     * {@code SELECT COUNT(*)}) or for arbitrary user-supplied SQL in {@link Query @Query} methods.</p>
     *
     * <p><b>Usage Examples:</b></p>
     * <pre>{@code
     * @DaoConfig(addLimitForSingleQuery = true)
     * public interface ProductDao extends CrudDao<Product, Long> {
     *     // Built-in Condition-based methods will have LIMIT 1 appended
     *     // when invoked through the proxy.
     * }
     * }</pre>
     *
     * @return {@code true} to auto-append the LIMIT clause to built-in single-result methods,
     *         {@code false} (default) otherwise
     */
    boolean addLimitForSingleQuery() default false;

    /**
     * Controls whether to automatically call {@code generateId()} for entity inserts
     * when the ID field is not set or has a default value.
     * 
     * <p>This applies to {@code CrudDao.insert(T entity)} and {@code CrudDao.batchInsert(Collection<T> entities)}
     * methods. The ID is considered "not set" when it's null or has the default value for its type
     * (0 for numeric types, null for objects).</p>
     * 
     * <p><b>Usage Examples:</b></p>
     * <pre>{@code
     * @DaoConfig(callGenerateIdForInsertIfIdNotSet = true)
     * public interface UserDao extends CrudDao<User, Long, SqlBuilder.PSC, UserDao> {
     *     @Override
     *     default Long generateId() {
     *         // Custom ID generation logic
     *         return System.currentTimeMillis() + ThreadLocalRandom.current().nextInt(1000);
     *     }
     * }
     * 
     * // Usage
     * User user = new User("John", "john@example.com");
     * userDao.insert(user);   // generateId() called automatically
     * }</pre>
     *
     * @return {@code true} to auto-generate IDs for insert operations
     */
    boolean callGenerateIdForInsertIfIdNotSet() default false;

    /**
     * Controls whether to automatically call {@code generateId()} for SQL-based entity inserts
     * when the ID field is not set or has a default value.
     * 
     * <p>This applies to {@code CrudDao.insert(String sql, T entity)} and 
     * {@code CrudDao.batchInsert(String sql, Collection<T> entities)} methods.
     * Similar to {@link #callGenerateIdForInsertIfIdNotSet()} but for custom SQL inserts.</p>
     * 
     * <p><b>Usage Examples:</b></p>
     * <pre>{@code
     * @DaoConfig(callGenerateIdForInsertWithSqlIfIdNotSet = true)
     * public interface OrderDao extends CrudDao<Order, Long> {
     *     default void insertWithAudit(Order order) {
     *         String sql = "INSERT INTO orders (id, customer_id, total, created_by) " +
     *                     "VALUES (:id, :customerId, :total, CURRENT_USER())";
     *         insert(sql, order);   // generateId() called if order.id not set
     *     }
     * }
     * }</pre>
     *
     * @return {@code true} to auto-generate IDs for SQL-based insert operations
     */
    boolean callGenerateIdForInsertWithSqlIfIdNotSet() default false;

    /**
     * Controls whether framework-managed join operations (driven by {@code @JoinedBy} entity annotations)
     * can be performed when the join key value is {@code null} or the type's default value.
     * When {@code false} (default), an {@code IllegalArgumentException} is thrown at runtime if a
     * null or default join key is encountered. When {@code true}, such joins are silently skipped
     * rather than raising an error.
     *
     * <p>This applies to the built-in join methods provided by {@code JoinEntityHelper}
     * (e.g., {@code listAllJoinEntities}, {@code findFirstWithJoinEntities}).
     * It does not affect user-written SQL in {@link Query @Query} methods.</p>
     *
     * <p><b>Usage Examples:</b></p>
     * <pre>{@code
     * @DaoConfig(allowJoiningByNullOrDefaultValue = true)
     * public interface CustomerDao extends CrudDao<Customer, Long> {
     *     // @JoinedBy-driven joins are allowed even if the join key is null or zero
     *     List<Customer> listAllWithPreferredContacts(Collection<String> selectPropNames);
     * }
     * }</pre>
     *
     * @return {@code true} to allow framework-managed joins when join key values are null or default;
     *         {@code false} (default) to throw an exception in that case
     */
    boolean allowJoiningByNullOrDefaultValue() default false;

    /**
     * Controls whether DataSet queries should fetch only columns that match entity class properties.
     * When true (default), DataSet queries will only include columns that correspond
     * to properties in the target entity class, similar to {@link FetchColumnByEntityClass}.
     *
     * <p>This provides consistency between entity queries and DataSet queries,
     * and can improve performance by reducing unnecessary data transfer.</p>
     * 
     * <p><b>Usage Examples:</b></p>
     * <pre>{@code
     * @DaoConfig(fetchColumnByEntityClassForDatasetQuery = false)
     * public interface ReportDao extends CrudDao<Report, Long> {
     *     // Will fetch all columns including calculated ones
     *     @Query("SELECT r.*, COUNT(d.id) as detail_count, SUM(d.amount) as total_amount " +
     *             "FROM reports r LEFT JOIN report_details d ON r.id = d.report_id " +
     *             "GROUP BY r.id")
     *     DataSet getReportSummaries();
     * }
     * }</pre>
     *
     * @return {@code true} to fetch only entity columns in DataSet queries
     */
    boolean fetchColumnByEntityClassForDatasetQuery() default true;
}
