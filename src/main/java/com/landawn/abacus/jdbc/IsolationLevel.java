/*
 * Copyright (C) 2015 HaiYang Li
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file except
 * in compliance with the License. You may obtain a copy of the License at
 *
 * https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software distributed under the License
 * is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express
 * or implied. See the License for the specific language governing permissions and limitations under
 * the License.
 */

package com.landawn.abacus.jdbc;

import java.sql.Connection;

/**
 * Enumeration representing the standard transaction isolation levels defined by JDBC.
 * These levels dictate how concurrent transactions interact with each other, specifically
 * concerning visibility of data modifications and potential data anomalies.
 *
 * <p>Choosing an appropriate isolation level is a critical decision that balances
 * data consistency requirements with application performance. Higher isolation levels
 * generally provide stronger data integrity guarantees but can lead to increased
 * resource locking and reduced concurrency.</p>
 *
 * <p>The isolation levels, ordered from least to most restrictive in terms of phenomena prevented, are:</p>
 * <ul>
 *   <li>{@link #READ_UNCOMMITTED}: The lowest level. Allows "dirty reads" (reading uncommitted changes),
 *       "non-repeatable reads" (reading the same data twice yields different results), and
 *       "phantom reads" (new rows appear in a range query).</li>
 *   <li>{@link #READ_COMMITTED}: Prevents dirty reads. A transaction can only read data that has
 *       been committed by other transactions. Still susceptible to non-repeatable reads and phantom reads.
 *       This is often the default for many database systems.</li>
 *   <li>{@link #REPEATABLE_READ}: Prevents dirty reads and non-repeatable reads. Ensures that if a
 *       transaction reads a row multiple times, it will always see the same data. Still susceptible to phantom reads.</li>
 *   <li>{@link #SERIALIZABLE}: The highest level. Prevents dirty reads, non-repeatable reads, and phantom reads.
 *       Transactions execute as if they were run serially, one after another, providing the strongest
 *       consistency but potentially the lowest concurrency.</li>
 * </ul>
 *
 * <p><b>Usage Examples:</b></p>
 * <pre>{@code
 * // Setting the isolation level for a Connection
 * try (Connection conn = dataSource.getConnection()) {
 *     conn.setTransactionIsolation(IsolationLevel.READ_COMMITTED.intValue());
 *     // ... perform transactional operations ...
 * } catch (SQLException e) {
 *     System.err.println("Database error: " + e.getMessage());
 * }
 *
 * // Retrieving the current isolation level
 * try (Connection conn = dataSource.getConnection()) {
 *     int currentJdbcLevel = conn.getTransactionIsolation();
 *     IsolationLevel currentLevel = IsolationLevel.valueOf(currentJdbcLevel);
 *     System.out.println("Current transaction isolation level: " + currentLevel);
 * } catch (SQLException e) {
 *     System.err.println("Database error: " + e.getMessage());
 * }
 * }</pre>
 *
 * @see Connection#setTransactionIsolation(int)
 * @see Connection#getTransactionIsolation()
 * @see <a href="https://docs.oracle.com/javase/tutorial/jdbc/basics/transactions.html">JDBC Transactions Tutorial</a>
 * @see <a href="https://en.wikipedia.org/wiki/Isolation_(database_systems)">Database Isolation Levels (Wikipedia)</a>
 */
public enum IsolationLevel {
    /**
     * Represents the default transaction isolation level of the underlying database.
     * When this level is specified, no explicit {@code Connection.setTransactionIsolation(...)}
     * call is made, allowing the database system to apply its own configured default.
     *
     * <p>This is often the most practical choice when the application relies on the
     * database administrator to configure the appropriate default isolation for the system,
     * or when a specific isolation level is not strictly required.</p>
     */
    DEFAULT(-1),

    /**
     * Indicates that transactions are not supported.
     * This level signifies that the database or driver does not provide transactional capabilities.
     *
     * @deprecated This isolation level is rarely encountered in modern relational databases
     *             and may not be supported by all JDBC drivers. It is generally recommended
     *             to use at least {@link #READ_UNCOMMITTED} if any form of concurrency control
     *             is desired, or to ensure the underlying data source supports transactions.
     */
    @Deprecated
    NONE(Connection.TRANSACTION_NONE),

    /**
     * The lowest transaction isolation level.
     * At this level, a transaction may read data that has been modified by other
     * transactions but not yet committed ("dirty reads"). It is also susceptible
     * to "non-repeatable reads" and "phantom reads."
     *
     * <p>While offering the highest concurrency and potentially the best performance,
     * {@code READ_UNCOMMITTED} provides the weakest data consistency guarantees.
     * Use this level only when temporary inconsistencies are acceptable and performance
     * is paramount.</p>
     *
     * <p>Corresponds to {@link Connection#TRANSACTION_READ_UNCOMMITTED}.</p>
     */
    READ_UNCOMMITTED(Connection.TRANSACTION_READ_UNCOMMITTED),

    /**
     * Prevents "dirty reads."
     * At this isolation level, a transaction can only read data that has been committed
     * by other transactions. This prevents a transaction from seeing intermediate,
     * uncommitted changes made by another transaction.
     *
     * <p>However, "non-repeatable reads" and "phantom reads" can still occur.
     * If a transaction reads the same row twice, and another transaction modifies
     * and commits that row between the two reads, the second read may yield different results.
     * Similarly, new rows matching a query's criteria might appear during a transaction.</p>
     *
     * <p>This is a common default isolation level for many database systems, offering a good
     * balance between data consistency and concurrency for most typical applications.</p>
     *
     * <p>Corresponds to {@link Connection#TRANSACTION_READ_COMMITTED}.</p>
     */
    READ_COMMITTED(Connection.TRANSACTION_READ_COMMITTED),

    /**
     * Prevents "dirty reads" and "non-repeatable reads."
     * At this isolation level, a transaction is guaranteed to read the same data
     * if it re-reads any row within the same transaction. This means that once a
     * row is read, no other transaction can modify or delete it until the reading
     * transaction commits or rolls back.
     *
     * <p>However, "phantom reads" can still occur. If a transaction executes a query
     * that returns a set of rows, and another transaction inserts new rows that
     * satisfy the query's {@code WHERE} clause, a subsequent execution of the same
     * query within the first transaction might return a different set of rows (including the new "phantom" rows).</p>
     *
     * <p>This level is suitable for applications requiring strong consistency for
     * individual rows read multiple times within a transaction, such as reporting
     * or analytical tasks where data integrity is crucial.</p>
     *
     * <p>Corresponds to {@link Connection#TRANSACTION_REPEATABLE_READ}.</p>
     */
    REPEATABLE_READ(Connection.TRANSACTION_REPEATABLE_READ),

    /**
     * The highest transaction isolation level.
     * This level prevents "dirty reads," "non-repeatable reads," and "phantom reads."
     * It ensures that transactions execute in a manner that produces the same result
     * as if they were executed one after another, serially.
     *
     * <p>{@code SERIALIZABLE} provides the strongest data consistency guarantees,
     * effectively eliminating all concurrency anomalies. However, this comes at the
     * cost of potentially significant performance degradation due to extensive locking
     * and reduced concurrency. It should be used only when absolute data integrity
     * is paramount and the performance impact is acceptable.</p>
     *
     * <p>Corresponds to {@link Connection#TRANSACTION_SERIALIZABLE}.</p>
     */
    SERIALIZABLE(Connection.TRANSACTION_SERIALIZABLE);

    /**
     * The integer value representing this isolation level, as defined in {@link Connection}.
     */
    private final int intValue;

    /**
     * Constructs an IsolationLevel with the specified integer value.
     *
     * @param intValue The JDBC constant value for this isolation level
     */
    IsolationLevel(final int intValue) {
        this.intValue = intValue;
    }

    /**
     * Returns the raw JDBC integer constant value associated with this {@code IsolationLevel}.
     * This value can be directly used with {@link Connection#setTransactionIsolation(int)}.
     *
     * <p><b>Usage Examples:</b></p>
     * <pre>{@code
     * try (Connection conn = dataSource.getConnection()) {
     *     // Set the transaction isolation level to READ_COMMITTED
     *     conn.setTransactionIsolation(IsolationLevel.READ_COMMITTED.intValue());
     *     // ... perform database operations ...
     * } catch (SQLException e) {
     *     System.err.println("Database error: " + e.getMessage());
     * }
     * }</pre>
     *
     * @return the JDBC integer constant value (e.g., {@link Connection#TRANSACTION_READ_COMMITTED}).
     */
    public int intValue() {
        return intValue;
    }

    /**
     * Returns the {@code IsolationLevel} enum constant that corresponds to the given
     * JDBC integer constant value.
     *
     * <p>This static factory method provides a convenient way to convert a raw JDBC
     * transaction isolation level integer (obtained, for example, from {@link Connection#getTransactionIsolation()})
     * into its type-safe {@code IsolationLevel} enum representation.</p>
     *
     * <p><b>Usage Examples:</b></p>
     * <pre>{@code
     * try (Connection conn = dataSource.getConnection()) {
     *     int jdbcIsolationLevel = conn.getTransactionIsolation();
     *     IsolationLevel enumIsolationLevel = IsolationLevel.valueOf(jdbcIsolationLevel);
     *     System.out.println("Detected isolation level: " + enumIsolationLevel);
     * } catch (SQLException e) {
     *     System.err.println("Database error: " + e.getMessage());
     * }
     * }</pre>
     *
     * @param intValue the JDBC integer constant representing a transaction isolation level
     *        (e.g., {@link Connection#TRANSACTION_READ_COMMITTED}).
     * @return the corresponding {@code IsolationLevel} enum constant.
     * @throws IllegalArgumentException if {@code intValue} does not match any known JDBC isolation level constant.
     */
    public static IsolationLevel valueOf(final int intValue) {
        switch (intValue) {
            case -1:
                return DEFAULT;

            case Connection.TRANSACTION_NONE:
                return NONE;

            case Connection.TRANSACTION_READ_UNCOMMITTED:
                return READ_UNCOMMITTED;

            case Connection.TRANSACTION_READ_COMMITTED:
                return READ_COMMITTED;

            case Connection.TRANSACTION_REPEATABLE_READ:
                return REPEATABLE_READ;

            case Connection.TRANSACTION_SERIALIZABLE:
                return SERIALIZABLE;

            default:
                throw new IllegalArgumentException("Invalid isolation level value: " + intValue + ".");
        }
    }
}