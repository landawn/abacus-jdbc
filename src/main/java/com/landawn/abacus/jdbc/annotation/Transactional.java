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

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

import com.landawn.abacus.jdbc.IsolationLevel;
import com.landawn.abacus.jdbc.Propagation;

/**
 * Declares transaction settings for a DAO method managed by the Abacus JDBC proxy.
 *
 * <p>The annotation selects a {@link Propagation propagation policy} and an
 * {@link IsolationLevel isolation level} for the method invocation. It is intended for DAO
 * methods; for service-layer transaction orchestration, prefer the transaction mechanism of
 * the surrounding framework (e.g., Spring's {@code @Transactional}).</p>
 *
 * <p>The DAO proxy ({@code DaoImpl}) inspects {@code @Transactional} when building the method
 * invocation chain. At invocation time the proxy:</p>
 * <ol>
 *   <li>Selects, joins, starts, suspends, or rejects a transaction context on the DAO's
 *       {@code DataSource}, as required by {@link #propagation()} and {@link #isolationLevel()}.</li>
 *   <li>Runs the method body.</li>
 *   <li>Commits an invocation-owned transaction on normal return, or rolls it back if an exception
 *       or error escapes. A transaction joined through {@code REQUIRED}, {@code SUPPORTS}, or
 *       {@code MANDATORY} remains governed by its outer owner.</li>
 * </ol>
 * The propagation rules follow the same semantics as the Spring equivalent: {@code REQUIRED}
 * joins an existing transaction or starts a new one, {@code REQUIRES_NEW} always starts a new
 * one (suspending any current transaction), {@code MANDATORY} requires an existing transaction,
 * etc.
 *
 * <p>This annotation may only be placed on methods (not on the DAO type). To make every method
 * transactional, mix in a base interface or apply {@code @Transactional} to each method
 * explicitly.</p>
 *
 * <p><b>Lazy stream limitation:</b> {@link Propagation#REQUIRED},
 * {@link Propagation#REQUIRES_NEW}, {@link Propagation#NOT_SUPPORTED}, and
 * {@link Propagation#NEVER} cannot be used on a method returning an
 * Abacus {@link com.landawn.abacus.util.stream.BaseStream} (including
 * {@link com.landawn.abacus.util.stream.Stream}) or a {@link java.util.stream.BaseStream}. Such a
 * stream performs work after the method returns: an invocation-owned transaction may already have
 * completed, while a temporarily suspended or prohibited transaction context may have changed.
 * The DAO rejects those combinations at initialization. Consume the stream inside a transactional,
 * non-stream-returning {@code default} method instead. {@link Propagation#SUPPORTS} and
 * {@link Propagation#MANDATORY} remain valid because the proxy neither owns nor suspends their
 * context; the caller must consume and close the stream before changing or completing that context.
 * {@code MANDATORY} still requires a caller-owned active transaction.</p>
 *
 * <p><b>Usage Examples:</b></p>
 * <pre>{@code
 * public interface OrderDao extends CrudDao<Order, Long, OrderDao> {
 *
 *     @Query(value = "INSERT INTO order_item (order_id, product_id, quantity) " +
 *                    "VALUES (:orderId, :productId, :quantity)", batch = true)
 *     void insertItems(List<OrderItem> items) throws SQLException;
 *
 *     // Default: REQUIRED + database-default isolation.
 *     @Transactional
 *     default void placeOrder(Order order, List<OrderItem> items) throws SQLException {
 *         insert(order);
 *         insertItems(items);                    // joins the same transaction
 *     }
 *
 *     // Independent audit record — survives even if the outer transaction rolls back.
 *     @Transactional(propagation = Propagation.REQUIRES_NEW)
 *     @Query("INSERT INTO audit_log (event, ts) VALUES (:event, :ts)")
 *     void logAudit(@Bind("event") String event, @Bind("ts") Instant ts) throws SQLException;
 * }
 *
 * public interface AccountDao extends CrudDao<Account, Long, AccountDao> {
 *     @Query("UPDATE account SET balance = balance - :amount WHERE id = :id")
 *     int debit(@Bind("id") long id, @Bind("amount") BigDecimal amount) throws SQLException;
 *
 *     @Query("UPDATE account SET balance = balance + :amount WHERE id = :id")
 *     int credit(@Bind("id") long id, @Bind("amount") BigDecimal amount) throws SQLException;
 *
 *     // Money transfer needs the strictest isolation.
 *     @Transactional(propagation = Propagation.REQUIRED,
 *                    isolationLevel = IsolationLevel.SERIALIZABLE)
 *     default void transfer(long from, long to, BigDecimal amount) throws SQLException {
 *         debit(from, amount);
 *         credit(to, amount);
 *     }
 * }
 * }</pre>
 *
 * @see Propagation
 * @see IsolationLevel
 * @see com.landawn.abacus.jdbc.Transaction
 * @see org.springframework.transaction.annotation.Transactional
 */
@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.METHOD) // Should be used on method only, not for ElementType.TYPE/CLASS
public @interface Transactional {

    /**
     * Specifies the transaction propagation behavior.
     * This determines how the method participates in existing transactions.
     *
     * <p>Common propagation behaviors:</p>
     * <ul>
     *   <li>{@link Propagation#REQUIRED} (default) - Join existing transaction or create new one</li>
     *   <li>{@link Propagation#REQUIRES_NEW} - Always create a new transaction</li>
     *   <li>{@link Propagation#SUPPORTS} - Use transaction if exists, otherwise non-transactional</li>
     *   <li>{@link Propagation#MANDATORY} - Must execute within existing transaction</li>
     *   <li>{@link Propagation#NOT_SUPPORTED} - Execute non-transactionally, suspend existing</li>
     *   <li>{@link Propagation#NEVER} - Execute non-transactionally, fail if transaction exists</li>
     * </ul>
     *
     * <p>Example scenarios:</p>
     * <pre>{@code
     * // Main business operation - needs transaction
     * @Transactional(propagation = Propagation.REQUIRED)
     * @Query("UPDATE orders SET status = :status WHERE id = :id")
     * int processOrder(@Bind("id") long id, @Bind("status") String status) throws SQLException;
     *
     * // Audit logging - independent transaction
     * @Transactional(propagation = Propagation.REQUIRES_NEW)
     * @Query("INSERT INTO activity_log (activity) VALUES (:activity)")
     * void logActivity(@Bind("activity") String activity) throws SQLException;
     *
     * // Read operation - works with or without transaction
     * @Transactional(propagation = Propagation.SUPPORTS)
     * @Query("SELECT * FROM users WHERE id = :id")
     * User getUser(@Bind("id") long id) throws SQLException;
     * }</pre>
     *
     * @return the configured propagation behavior; defaults to {@link Propagation#REQUIRED}
     * @see Propagation
     */
    Propagation propagation() default Propagation.REQUIRED;

    /**
     * Specifies the transaction isolation level.
     * This controls how the transaction interacts with other concurrent transactions.
     *
     * <p>Isolation levels (from least to most restrictive):</p>
     * <ul>
     *   <li>{@link IsolationLevel#DEFAULT} - Leave the connection at its configured/default isolation</li>
     *   <li>{@link IsolationLevel#READ_UNCOMMITTED} - Allows dirty, non-repeatable, and phantom reads</li>
     *   <li>{@link IsolationLevel#READ_COMMITTED} - Prevents dirty reads</li>
     *   <li>{@link IsolationLevel#REPEATABLE_READ} - Prevents dirty and non-repeatable reads</li>
     *   <li>{@link IsolationLevel#SERIALIZABLE} - Prevents the standard dirty-read,
     *       non-repeatable-read, and phantom-read phenomena</li>
     * </ul>
     *
     * <p>Choose isolation level based on your consistency requirements:</p>
     * <pre>{@code
     * // Financial transactions need high isolation
     * @Transactional(isolationLevel = IsolationLevel.SERIALIZABLE)
     * @Query("UPDATE account SET balance = balance + :amount WHERE id = :id")
     * int adjustBalance(@Bind("id") long id, @Bind("amount") BigDecimal amount) throws SQLException;
     *
     * // Reporting can tolerate some inconsistency
     * @Transactional(isolationLevel = IsolationLevel.READ_UNCOMMITTED)
     * @Query("SELECT * FROM report_data")
     * List<Report> generateReports() throws SQLException;
     *
     * // Most business operations use default
     * @Transactional(isolationLevel = IsolationLevel.DEFAULT)
     * @Query("UPDATE users SET display_name = :displayName WHERE id = :id")
     * int updateUserProfile(User user) throws SQLException;
     * }</pre>
     *
     * <p><strong>Note:</strong> Higher isolation levels may impact performance due to
     * increased locking. Choose the lowest level that meets your consistency requirements.</p>
     *
     * @return the configured isolation level; defaults to {@link IsolationLevel#DEFAULT}
     * @see IsolationLevel
     */
    IsolationLevel isolationLevel() default IsolationLevel.DEFAULT;
}
