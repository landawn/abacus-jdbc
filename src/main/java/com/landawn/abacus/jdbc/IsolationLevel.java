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
 * Represents the transaction isolation levels supported by JDBC.
 * 
 * <p>Transaction isolation levels define how transactions interact with each other
 * when accessing the same data. Higher isolation levels provide better data consistency
 * but may impact performance due to increased locking.</p>
 * 
 * <p>The isolation levels from least to most restrictive are:
 * <ul>
 *   <li>{@link #READ_UNCOMMITTED} - Allows dirty reads, non-repeatable reads, and phantom reads</li>
 *   <li>{@link #READ_COMMITTED} - Prevents dirty reads but allows non-repeatable reads and phantom reads</li>
 *   <li>{@link #REPEATABLE_READ} - Prevents dirty reads and non-repeatable reads but allows phantom reads</li>
 *   <li>{@link #SERIALIZABLE} - Prevents all phenomena, providing the highest isolation</li>
 * </ul>
 * 
 * <p>Usage example:
 * <pre>{@code
 * Connection conn = dataSource.getConnection();
 * conn.setTransactionIsolation(IsolationLevel.READ_COMMITTED.intValue());
 * 
 * // Or use with a transaction manager
 * IsolationLevel level = IsolationLevel.valueOf(conn.getTransactionIsolation());
 * }</pre>
 *
 * @see Connection#setTransactionIsolation(int)
 * @see <a href="http://docs.oracle.com/javase/tutorial/jdbc/basics/transactions.html">JDBC Transactions Tutorial</a>
 */
public enum IsolationLevel {
    /**
     * Special value indicating that the default transaction isolation level should be used.
     * When this level is specified, no {@code Connection.setTransactionIsolation(...)} call
     * will be made, allowing the database's default isolation level to be used.
     * 
     * <p>This is useful when you want to rely on the database's configured default
     * rather than explicitly setting an isolation level.</p>
     */
    DEFAULT(-1),

    /**
     * A constant indicating that transactions are not supported.
     * 
     * @deprecated This isolation level is rarely used and may not be supported by all databases.
     *             Consider using {@link #READ_UNCOMMITTED} as the lowest isolation level instead.
     */
    @Deprecated
    NONE(Connection.TRANSACTION_NONE),

    /**
     * Dirty reads, non-repeatable reads, and phantom reads can occur.
     * 
     * <p>This isolation level allows a transaction to read data that has been modified
     * by other transactions but not yet committed. This is the lowest isolation level
     * and provides the best performance but the least data consistency.</p>
     * 
     * <p>Use cases: When performance is critical and temporary inconsistencies are acceptable,
     * such as in reporting systems reading from replicated data.</p>
     */
    READ_UNCOMMITTED(Connection.TRANSACTION_READ_UNCOMMITTED),

    /**
     * Dirty reads are prevented; non-repeatable reads and phantom reads can occur.
     * 
     * <p>This is the default isolation level for many databases. It ensures that a transaction
     * can only read data that has been committed by other transactions, preventing dirty reads.
     * However, if the same row is read twice in a transaction, it may return different values
     * if another transaction modifies and commits the data between the reads.</p>
     * 
     * <p>Use cases: Most typical database applications where preventing dirty reads is
     * sufficient and some inconsistency is acceptable.</p>
     */
    READ_COMMITTED(Connection.TRANSACTION_READ_COMMITTED),

    /**
     * Dirty reads and non-repeatable reads are prevented; phantom reads can occur.
     * 
     * <p>This isolation level ensures that if a transaction reads a row, subsequent reads
     * of the same row within the transaction will return the same data, even if other
     * transactions attempt to modify it. However, other transactions may insert new rows
     * that match the query criteria (phantom reads).</p>
     * 
     * <p>Use cases: Applications that need consistent reads within a transaction,
     * such as financial calculations where values must not change during processing.</p>
     */
    REPEATABLE_READ(Connection.TRANSACTION_REPEATABLE_READ),

    /**
     * Dirty reads, non-repeatable reads, and phantom reads are prevented.
     * 
     * <p>This is the highest isolation level, effectively serializing access to data.
     * Transactions execute as if they were running sequentially rather than concurrently.
     * This provides the strongest consistency guarantees but may significantly impact
     * performance due to extensive locking.</p>
     * 
     * <p>Use cases: Critical operations requiring absolute consistency, such as
     * financial transfers or inventory management where no anomalies can be tolerated.</p>
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
     * Returns the integer value of this isolation level as defined in {@link Connection}.
     * 
     * <p><b>Usage Examples:</b></p>
     * <pre>{@code
     * Connection conn = dataSource.getConnection();
     * conn.setTransactionIsolation(IsolationLevel.SERIALIZABLE.intValue());
     * }</pre>
     *
     * @return The JDBC constant value for this isolation level
     */
    public int intValue() {
        return intValue;
    }

    /**
     * Returns the IsolationLevel enum constant corresponding to the specified integer value.
     * 
     * <p><b>Usage Examples:</b></p>
     * <pre>{@code
     * Connection conn = dataSource.getConnection();
     * int currentLevel = conn.getTransactionIsolation();
     * IsolationLevel level = IsolationLevel.valueOf(currentLevel);
     * System.out.println("Current isolation: " + level);
     * }</pre>
     *
     * @param intValue The JDBC constant value for the isolation level
     * @return The corresponding IsolationLevel enum constant
     * @throws IllegalArgumentException if the specified value does not correspond to any isolation level
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