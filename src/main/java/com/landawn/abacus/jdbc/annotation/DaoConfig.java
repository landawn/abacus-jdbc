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
 *   <li>Column fetching strategies for Dataset queries</li>
 * </ul>
 * 
 * <p><b>Usage Examples:</b></p>
 * <pre>{@code
 * @DaoConfig(
 *     addLimitForSingleQuery = true,
 *     callGenerateIdForInsertIfIdNotSet = true
 * )
 * public interface UserDao extends CrudDao<User, Long> {
 *     // Single query methods will automatically add LIMIT 1
 *     @Query("SELECT * FROM users WHERE email = :email")
 *     User findByEmail(@Bind("email") String email);  // LIMIT 1 added automatically
 *
 *     // ID generation will be called if user.id is null or 0
 *     default User createUser(String name, String email) {
 *         User user = new User(name, email);
 *         insert(user);  // generateId() called automatically if needed
 *         return user;
 *     }
 * }
 * 
 * @DaoConfig(allowJoiningByNullOrDefaultValue = true)
 * public interface OrderDao extends CrudDao<Order, Long> {
 *     // Allows joins even when foreign key might be null
 *     @Query("SELECT * FROM orders o LEFT JOIN customers c ON o.customer_id = c.id")
 *     List<Order> findAllOrdersWithCustomers();
 * }
 * }</pre>
 *
 * @see CrudDao
 */
@Retention(RetentionPolicy.RUNTIME)
@Target(value = { ElementType.TYPE })
public @interface DaoConfig {

    /**
     * Controls whether to automatically add LIMIT clause to single-result query methods.
     * When {@code true}, methods that return a single result will have {@code LIMIT 1}
     * (or equivalent) added to their SQL queries for better performance.
     * 
     * <p>Single query methods include:</p>
     * <ul>
     *   <li>{@code queryForSingleXxx()} methods</li>
     *   <li>{@code queryForUniqueResult()}</li>
     *   <li>{@code findFirst()}</li>
     *   <li>{@code findOnlyOne()}</li>
     *   <li>{@code exists()}</li>
     *   <li>{@code count()} (when not using COUNT in SQL)</li>
     * </ul>
     * 
     * <p><b>Usage Examples:</b></p>
     * <pre>{@code
     * @DaoConfig(addLimitForSingleQuery = true)
     * public interface ProductDao extends CrudDao<Product, Long> {
     *     @Query("SELECT * FROM products WHERE code = :code")
     *     Product findByCode(@Bind("code") String code);
     *     // Executed as: SELECT * FROM products WHERE code = ? LIMIT 1
     * }
     * }</pre>
     *
     * @return {@code true} to auto-add LIMIT clause, {@code false} otherwise
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
     * public interface UserDao extends CrudDao<User, Long> {
     *     @Override
     *     default Long generateId() {
     *         // Custom ID generation logic
     *         return System.currentTimeMillis() + ThreadLocalRandom.current().nextInt(1000);
     *     }
     * }
     * 
     * // Usage
     * User user = new User("John", "john@example.com");
     * userDao.insert(user);  // generateId() called automatically
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
     *         insert(sql, order);  // generateId() called if order.id not set
     *     }
     * }
     * }</pre>
     *
     * @return {@code true} to auto-generate IDs for SQL-based insert operations
     */
    boolean callGenerateIdForInsertWithSqlIfIdNotSet() default false;

    /**
     * Controls whether joins can be performed using null or default values in join conditions.
     * When false (default), joins with null values are skipped for safety.
     * When true, allows joins even when the joining column contains null.
     * 
     * <p>This is useful for outer joins where {@code null} values are expected and valid.</p>
     * 
     * <p><b>Usage Examples:</b></p>
     * <pre>{@code
     * @DaoConfig(allowJoiningByNullOrDefaultValue = true)
     * public interface CustomerDao extends CrudDao<Customer, Long> {
     *     // Allows join even if preferred_contact_id is null
     *     @Query("SELECT c.*, p.* FROM customers c " +
     *             "LEFT JOIN contacts p ON c.preferred_contact_id = p.id")
     *     @MergedById
     *     List<Customer> findAllWithPreferredContacts();
     * }
     * }</pre>
     *
     * @return {@code true} to allow joins with null/default values
     */
    boolean allowJoiningByNullOrDefaultValue() default false;

    /**
     * Controls whether Dataset queries should fetch only columns that match entity class properties.
     * When true (default), Dataset queries will only include columns that correspond
     * to properties in the target entity class, similar to {@link FetchColumnByEntityClass}.
     * 
     * <p>This provides consistency between entity queries and Dataset queries,
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
     *     Dataset getReportSummaries();
     * }
     * }</pre>
     *
     * @return {@code true} to fetch only entity columns in Dataset queries
     */
    boolean fetchColumnByEntityClassForDatasetQuery() default true;
}
