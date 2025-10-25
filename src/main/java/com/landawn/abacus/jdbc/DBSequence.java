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
import java.sql.SQLException;
import java.sql.Timestamp;
import java.util.concurrent.atomic.AtomicLong;

import javax.sql.DataSource;

import com.landawn.abacus.exception.UncheckedSQLException;
import com.landawn.abacus.logging.Logger;
import com.landawn.abacus.logging.LoggerFactory;
import com.landawn.abacus.util.Dates;
import com.landawn.abacus.util.Dates.DateUtil;
import com.landawn.abacus.util.Strings;

/**
 * Provides distributed sequence generation using a database table as the backing store.
 * 
 * <p>This class implements a high-performance sequence generator that minimizes database
 * round trips by allocating blocks of sequence values. It's suitable for generating
 * unique identifiers across multiple application instances or processes.</p>
 * 
 * <p>The sequence mechanism uses a database table with the following structure:
 * <ul>
 *   <li>seq_name: The name of the sequence (unique)</li>
 *   <li>next_val: The next available sequence value</li>
 *   <li>update_time: Last update timestamp</li>
 *   <li>create_time: Sequence creation timestamp</li>
 * </ul>
 * 
 * <p>Features:
 * <ul>
 *   <li>Thread-safe sequence generation</li>
 *   <li>Configurable buffer size to reduce database access</li>
 *   <li>Automatic table creation and initialization</li>
 *   <li>Support for multiple named sequences in the same table</li>
 *   <li>Ability to reset sequences with new starting values</li>
 * </ul>
 * 
 * <p>Usage example:
 * <pre>{@code
 * DBSequence sequence = new DBSequence(dataSource, "sequences", "order_id", 1000, 100);
 * 
 * // Generate unique IDs
 * long id1 = sequence.nextVal(); // 1000
 * long id2 = sequence.nextVal(); // 1001
 * // ... up to 100 values before next DB access
 * 
 * // Reset sequence if needed
 * sequence.reset(5000, 50);
 * }</pre>
 * 
 * @see DataSource
 * @since 1.0
 */
public final class DBSequence {

    private static final Logger logger = LoggerFactory.getLogger(DBSequence.class);

    private final DataSource ds;

    private final String seqName;

    private int seqBufferSize;

    private final String querySQL;

    private final String updateSQL;

    private final String resetSQL;

    private final AtomicLong lowSeqId;

    private final AtomicLong highSeqId;

    /**
     * Constructs a new DBSequence with the specified parameters.
     * 
     * <p>Creates the sequence table if it doesn't exist and initializes the sequence
     * with the specified starting value if it doesn't already exist. If the sequence
     * exists with a value lower than startVal, it will be updated to startVal.</p>
     * 
     * <p><b>Usage Examples:</b></p>
     * <pre>{@code
     * // Create a sequence starting at 1000 with a buffer of 50
     * DBSequence seq = new DBSequence(dataSource, "id_sequences", "user_id", 1000, 50);
     * }</pre>
     * 
     * @param ds The data source to use for database connections
     * @param tableName The name of the table to store sequences (created if not exists)
     * @param seqName The name of this specific sequence within the table
     * @param startVal The initial value for the sequence (must be non-negative)
     * @param seqBufferSize The number of sequence values to cache in memory (must be greater than 0)
     * @throws IllegalArgumentException if tableName or seqName is empty, startVal is negative, or seqBufferSize is not positive
     * @throws RuntimeException if the sequence table or sequence cannot be created/initialized
     * @throws UncheckedSQLException if database operations fail
     */
    DBSequence(final DataSource ds, final String tableName, final String seqName, final long startVal, final int seqBufferSize) {
        this.ds = ds;
        this.seqName = seqName;
        this.seqBufferSize = seqBufferSize;

        if (Strings.isEmpty(tableName)) {
            throw new IllegalArgumentException("Table name can't be null or empty");
        }

        if (Strings.isEmpty(seqName)) {
            throw new IllegalArgumentException("Sequence name can't be null or empty");
        }

        if (startVal < 0) {
            throw new IllegalArgumentException("startVal can't be negative");
        }

        if (seqBufferSize <= 0) {
            throw new IllegalArgumentException("seqBufferSize must be greater than 0");
        }

        querySQL = "SELECT next_val FROM " + tableName + " WHERE seq_name = ?"; //NOSONAR
        updateSQL = "UPDATE " + tableName + " SET next_val = ?, update_time = ? WHERE next_val = ? AND seq_name = ?"; //NOSONAR
        resetSQL = "UPDATE " + tableName + " SET next_val = ?, update_time = ? WHERE seq_name = ?"; //NOSONAR
        lowSeqId = new AtomicLong(startVal);
        highSeqId = new AtomicLong(startVal);

        final String schema = "CREATE TABLE " + tableName
                + "(seq_name VARCHAR(64), next_val BIGINT, update_time TIMESTAMP NOT NULL, create_time TIMESTAMP NOT NULL, UNIQUE (seq_name))";

        final Connection conn = JdbcUtil.getConnection(ds);

        try { //NOSONAR
            if (!JdbcUtil.doesTableExist(conn, tableName)) {
                try { //NOSONAR
                    JdbcUtil.createTableIfNotExists(conn, tableName, schema);
                } catch (final Exception e) {
                    if (logger.isWarnEnabled()) {
                        logger.warn("Failed to create table: " + tableName);
                    }
                }

                if (!JdbcUtil.doesTableExist(conn, tableName)) {
                    throw new RuntimeException("Failed to create table: " + tableName);
                }
            }

            final Timestamp now = Dates.currentTimestamp();

            if (JdbcUtil.prepareQuery(conn, "SELECT 1 FROM " + tableName + " WHERE seq_name = ?").setString(1, seqName).queryForInt().orElse(0) < 1) {
                try { //NOSONAR
                    JdbcUtil.executeUpdate(conn, "INSERT INTO " + tableName + "(seq_name, next_val, update_time, create_time) VALUES (?, ?, ?, ?)", seqName,
                            startVal, now, now);
                } catch (final Exception e) {
                    if (logger.isWarnEnabled()) {
                        logger.warn("Failed to initialize sequence: " + seqName + " within table: " + tableName);
                    }
                }
            }

            JdbcUtil.executeUpdate(conn, "UPDATE " + tableName + " SET next_val = ?, update_time = ? WHERE seq_name = ? AND next_val < ?", startVal, now,
                    seqName, startVal);

            if (JdbcUtil.prepareQuery(conn, "SELECT next_val FROM " + tableName + " WHERE seq_name = ?")
                    .setString(1, seqName)
                    .queryForLong()
                    .orElse(0) < startVal) {
                throw new RuntimeException("Failed to initialize sequence: " + seqName + " within table: " + tableName);
            }
        } catch (final SQLException e) {
            throw new UncheckedSQLException(e);
        } finally {
            JdbcUtil.releaseConnection(conn, ds);
        }
    }

    /**
     * Retrieves the next value in the sequence.
     * 
     * <p>This method is thread-safe and synchronized on the sequence name to ensure
     * proper ordering across threads. When the local buffer is exhausted, it fetches
     * the next block of sequence values from the database.</p>
     * 
     * <p>The method uses optimistic locking to handle concurrent access from multiple
     * application instances. If another instance has already claimed the next block,
     * this method will retry until it successfully reserves a block.</p>
     * 
     * <p><b>Usage Examples:</b></p>
     * <pre>{@code
     * DBSequence sequence = new DBSequence(ds, "sequences", "order_id", 1000, 100);
     * 
     * // Generate unique order IDs
     * for (int i = 0; i < 10; i++) {
     *     long orderId = sequence.nextVal();
     *     System.out.println("Order ID: " + orderId);
     * }
     * }</pre>
     *
     * @return the next value in the sequence
     * @throws UncheckedSQLException if a database access error occurs
     */
    public long nextVal() {
        synchronized (seqName) { //NOSONAR
            try {
                int retryCount = 0;
                final int maxRetries = 1000;

                while (lowSeqId.get() >= highSeqId.get()) {
                    if (++retryCount > maxRetries) {
                        throw new SQLException(
                                "Failed to acquire sequence '" + seqName + "' after " + maxRetries + " attempts. Possible high contention or database issue.");
                    }

                    try (PreparedQuery query = JdbcUtil.prepareQuery(ds, querySQL)) {
                        lowSeqId.set(query.setString(1, seqName).queryForLong().orElse(0));
                    }

                    if (JdbcUtil.executeUpdate(ds, updateSQL, lowSeqId.get() + seqBufferSize, DateUtil.currentTimestamp(), lowSeqId.get(), seqName) > 0) {
                        highSeqId.set(lowSeqId.get() + seqBufferSize);

                        break;
                    }
                }
            } catch (final SQLException e) {
                throw new UncheckedSQLException(e);
            }
        }

        return lowSeqId.getAndIncrement();
    }

    /**
     * Resets the sequence to the specified start value and buffer size.
     * 
     * <p>This method updates both the database and the in-memory state of the sequence.
     * Any cached values are discarded, and the next call to {@link #nextVal()} will
     * return the new startVal.</p>
     * 
     * <p><strong>Warning:</strong> Resetting a sequence to a lower value than previously
     * generated values may cause duplicate IDs. Use with caution in production systems.</p>
     * 
     * <p><b>Usage Examples:</b></p>
     * <pre>{@code
     * DBSequence sequence = new DBSequence(ds, "sequences", "temp_id", 1, 10);
     * 
     * // Use sequence...
     * long id = sequence.nextVal(); // Returns 1
     * 
     * // Reset to start from 1000 with larger buffer
     * sequence.reset(1000, 100);
     * id = sequence.nextVal(); // Returns 1000
     * }</pre>
     *
     * @param startVal The new starting value for the sequence
     * @param seqBufferSize The new buffer size for the sequence
     * @throws UncheckedSQLException if a database access error occurs
     */
    @SuppressWarnings("hiding")
    public void reset(final long startVal, final int seqBufferSize) {
        synchronized (seqName) { //NOSONAR
            this.seqBufferSize = seqBufferSize;
            this.lowSeqId.set(startVal);
            this.highSeqId.set(startVal);

            try {
                JdbcUtil.executeUpdate(ds, resetSQL, startVal, DateUtil.currentTimestamp(), seqName);
            } catch (final SQLException e) {
                throw new UncheckedSQLException(e);
            }
        }
    }
}