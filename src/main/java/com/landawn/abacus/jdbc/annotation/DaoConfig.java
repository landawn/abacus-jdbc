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
 *     addLimitForSingleQuery            = true,
 *     callGenerateIdForInsertIfIdNotSet = true
 * )
 * public interface UserDao extends CrudDao<User, Long, UserDao> {
 *     // The inherited Condition-based single-result methods (e.g. findFirst, findOnlyOne, exists)
 *     // will have a LIMIT clause appended automatically, for example:
 *     //     Optional<User> user = userDao.findFirst(CF.eq("email", email));   // LIMIT 1 added automatically
 *     // User-supplied SQL in @Query methods is left untouched.
 *
 *     // ID generation will be called if user.id is null or 0
 *     default User createUser(String name, String email) throws SQLException {
 *         User user = new User(name, email);
 *         insert(user);   // generateId() called automatically if needed
 *         return user;
 *     }
 * }
 *
 * @DaoConfig(allowJoiningByNullOrDefaultValue = true)
 * public interface OrderDao extends CrudDao<Order, Long, OrderDao> {
 *     // Framework-managed @JoinedBy joins are allowed even when the join key is null:
 *     // use the inherited JoinEntityHelper methods (e.g. loadJoinEntities, listAllJoinEntities).
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
     *   <li>{@code LIMIT 1} for {@code exists(Condition)}, {@code findFirst(...)} and {@code queryForSingleXxx(...)} variants</li>
     *   <li>{@code LIMIT 2} for {@code findOnlyOne(...)} and {@code queryForUniqueXxx(...)} variants (so duplicates can still be detected)</li>
     * </ul>
     *
     * <p>The LIMIT is <em>not</em> added for {@code count(Condition)} (which already issues a
     * {@code SELECT COUNT(*)}) or for arbitrary user-supplied SQL in {@link Query @Query} methods.</p>
     *
     * <p><b>Usage Examples:</b></p>
     * <pre>{@code
     * @DaoConfig(addLimitForSingleQuery = true)
     * public interface ProductDao extends CrudDao<Product, Long, ProductDao> {
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
     * public interface UserDao extends CrudDao<User, Long, UserDao> {
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
     * public interface OrderDao extends CrudDao<Order, Long, OrderDao> {
     *     default void insertWithAudit(Order order) throws SQLException {
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
     * null or default join key is encountered. When {@code true}, the join query is executed with the
     * null/default key (typically loading no join entities) instead of raising an error.
     *
     * <p>This applies to the built-in join methods provided by {@code JoinEntityHelper}
     * (e.g., {@code listAllJoinEntities}, {@code findFirstWithJoinEntities}).
     * It does not affect user-written SQL in {@link Query @Query} methods.</p>
     *
     * <p><b>Usage Examples:</b></p>
     * <pre>{@code
     * @DaoConfig(allowJoiningByNullOrDefaultValue = true)
     * public interface CustomerDao extends CrudDao<Customer, Long, CustomerDao> {
     *     // @JoinedBy-driven joins are allowed even if the join key is null or zero:
     *     // use the inherited JoinEntityHelper methods (e.g. loadJoinEntities, listAllJoinEntities).
     * }
     * }</pre>
     *
     * @return {@code true} to allow framework-managed joins when join key values are null or default;
     *         {@code false} (default) to throw an exception in that case
     */
    boolean allowJoiningByNullOrDefaultValue() default false;

    /**
     * Controls the default column-selection behavior of built-in {@code Dataset}-returning
     * methods (e.g., {@code query(Condition)}, {@code query(Collection<String>, Condition)})
     * provided by {@code Dao}/{@code CrudDao}, when individual call sites do not override it
     * with {@link FetchColumnByEntityClass @FetchColumnByEntityClass}.
     *
     * <p>When {@code true} (default), the framework restricts the projected columns to those
     * that map to properties on the DAO's target entity class, producing a {@code Dataset} that
     * mirrors the entity shape. When {@code false}, the underlying SELECT keeps every column
     * referenced by the SQL (useful for joins with extra columns, calculated columns, or
     * aggregations that have no corresponding entity property).</p>
     *
     * <p>This setting applies to every built-in <em>and</em> {@link Query @Query}-based method that returns a
     * {@code Dataset} and does not carry a method-level {@link FetchColumnByEntityClass @FetchColumnByEntityClass}
     * (which always wins over this default). The SQL text declared by {@code @Query} is never rewritten; only the
     * set of columns retained in the resulting {@code Dataset} is affected.</p>
     *
     * <p><b>Usage Examples:</b></p>
     * <pre>{@code
     * @DaoConfig(fetchColumnByEntityClassForDatasetQuery = false)
     * public interface ReportDao extends CrudDao<Report, Long, ReportDao> {
     *     // Will fetch all columns including calculated ones
     *     @Query("SELECT r.*, COUNT(d.id) as detail_count, SUM(d.amount) as total_amount " +
     *             "FROM reports r LEFT JOIN report_details d ON r.id = d.report_id " +
     *             "GROUP BY r.id")
     *     Dataset getReportSummaries() throws SQLException;
     * }
     * }</pre>
     *
     * @return {@code true} (default) to restrict built-in {@code Dataset} queries to entity-mapped columns;
     *         {@code false} to retain every column referenced by the SELECT
     */
    boolean fetchColumnByEntityClassForDatasetQuery() default true;
}
