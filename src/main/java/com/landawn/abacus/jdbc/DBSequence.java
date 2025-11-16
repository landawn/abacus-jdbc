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
 * Implements a high-performance distributed sequence generator backed by a database table.
 * This class is designed to provide unique, monotonically increasing identifiers across
 * multiple application instances or processes in a distributed environment.
 *
 * <p>To minimize database round trips and enhance performance, {@code DBSequence} employs
 * a buffering strategy: it allocates and caches blocks of sequence values in memory.
 * When the in-memory buffer is exhausted, a new block is fetched from the database.</p>
 *
 * <p>The sequence values are stored and managed within a dedicated database table with the following schema:</p>
 * <ul>
 *   <li>{@code seq_name}: A unique identifier for each sequence (e.g., "user_id_seq", "order_number_seq").</li>
 *   <li>{@code next_val}: The next available sequence value to be allocated from the database.</li>
 *   <li>{@code update_time}: Timestamp of the last update to this sequence record.</li>
 *   <li>{@code create_time}: Timestamp of the initial creation of this sequence record.</li>
 * </ul>
 *
 * <p>Key features and benefits:</p>
 * <ul>
 *   <li><b>High Performance:</b> Reduces database load by fetching sequence numbers in configurable blocks.</li>
 *   <li><b>Distributed Safety:</b> Ensures unique ID generation across multiple application nodes.</li>
 *   <li><b>Thread-Safe:</b> {@code nextVal()} method is synchronized to guarantee correct sequence allocation in multi-threaded contexts.</li>
 *   <li><b>Configurable Buffer:</b> Allows tuning the {@code seqBufferSize} to balance database access frequency and memory usage.</li>
 *   <li><b>Automatic Management:</b> Handles automatic table creation and sequence initialization.</li>
 *   <li><b>Multiple Sequences:</b> Supports managing multiple distinct named sequences within the same table.</li>
 *   <li><b>Sequence Reset:</b> Provides functionality to reset a sequence to a new starting value.</li>
 * </ul>
 *
 * <p><b>Usage Examples:</b></p>
 * <pre>{@code
 * // Initialize a sequence named "order_id" in table "app_sequences"
 * // starting at 1000, with a buffer size of 100.
 * DBSequence orderSequence = new DBSequence(dataSource, "app_sequences", "order_id", 1000, 100);
 *
 * // Generate unique order IDs
 * long id1 = orderSequence.nextVal(); // Might return 1000
 * long id2 = orderSequence.nextVal(); // Might return 1001
 * // ... up to 100 calls can be made before another database round trip is needed.
 *
 * // Reset the sequence to a new starting value and buffer size
 * orderSequence.reset(5000, 50);
 * long id3 = orderSequence.nextVal(); // Might return 5000
 * }</pre>
 *
 * @see DataSource
 * @see JdbcUtil
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
     * Constructs a new {@code DBSequence} instance, initializing or configuring a named sequence
     * within the specified database table.
     *
     * <p>This constructor ensures the sequence table exists and sets up the initial state
     * for the named sequence. It performs the following operations:</p>
     * <ol>
     *   <li><b>Table Creation:</b> Verifies the existence of {@code tableName}. If it doesn't exist,
     *       it attempts to create it with the necessary schema ({@code seq_name}, {@code next_val},
     *       {@code update_time}, {@code create_time}).</li>
     *   <li><b>Sequence Initialization:</b> If the specified {@code seqName} does not exist in the table,
     *       a new sequence record is inserted with {@code startVal} as its initial {@code next_val}.</li>
     *   <li><b>Sequence Update:</b> If {@code seqName} already exists but its {@code next_val} is less
     *       than the provided {@code startVal}, the {@code next_val} in the database is updated to {@code startVal}.</li>
     *   <li><b>SQL Preparation:</b> Prepares the internal SQL statements used for querying and updating
     *       the sequence values in the database.</li>
     * </ol>
     *
     * <p><b>Usage Examples:</b></p>
     * <pre>{@code
     * // Initialize a sequence for "product_id" in "app_sequences" table
     * // starting from 1, with a buffer of 200 IDs.
     * DBSequence productSequence = new DBSequence(dataSource, "app_sequences", "product_id", 1, 200);
     *
     * // Initialize another sequence for "invoice_number" starting from 10000, buffer 50.
     * DBSequence invoiceSequence = new DBSequence(dataSource, "app_sequences", "invoice_number", 10000, 50);
     *
     * // Generate IDs
     * long nextProductId = productSequence.nextVal();
     * long nextInvoiceNumber = invoiceSequence.nextVal();
     * }</pre>
     *
     * @param ds the {@link DataSource} to use for database connections. Must not be {@code null}.
     * @param tableName the name of the database table used to store sequence metadata. Must not be {@code null} or empty.
     * @param seqName the unique name of this sequence (e.g., "user_id_seq"). Must not be {@code null} or empty.
     * @param startVal the initial value for the sequence. Must be non-negative.
     * @param seqBufferSize the number of sequence values to pre-fetch and cache in memory. Must be greater than 0.
     * @throws IllegalArgumentException if {@code tableName} or {@code seqName} is empty, {@code startVal} is negative,
     *         or {@code seqBufferSize} is not positive.
     * @throws RuntimeException if the sequence table cannot be created or initialized, or if the sequence
     *         cannot be set to the desired {@code startVal}.
     * @throws UncheckedSQLException if a database access error occurs during initialization.
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
     * Retrieves the next unique value from this sequence.
     *
     * <p>This method is thread-safe and ensures that each call returns a unique, monotonically
     * increasing value. It operates by first checking the in-memory buffer. If the buffer
     * is not exhausted, it simply increments and returns the next buffered value, avoiding
     * a database round trip.</p>
     *
     * <p>When the in-memory buffer is exhausted (i.e., {@code lowSeqId} catches up to {@code highSeqId}),
     * this method performs a database transaction to fetch the next block of sequence values.
     * This database operation uses optimistic locking to safely reserve a new block, handling
     * concurrent requests from other threads or application instances. If contention is high,
     * it will retry fetching a new block up to a maximum number of attempts.</p>
     *
     * <p><b>Performance Note:</b> The buffering strategy significantly reduces the number of
     * database interactions. For example, with a {@code seqBufferSize} of 100, only one database
     * update is required for every 100 calls to {@code nextVal()}, drastically improving throughput.</p>
     *
     * <p><b>Usage Examples:</b></p>
     * <pre>{@code
     * DBSequence userSequence = new DBSequence(dataSource, "app_sequences", "user_id", 1, 50);
     *
     * // Generate a single user ID
     * long newUserId = userSequence.nextVal();
     * System.out.println("New User ID: " + newUserId);
     *
     * // Generate multiple IDs in a loop
     * for (int i = 0; i < 5; i++) {
     *     long id = userSequence.nextVal();
     *     System.out.println("Generated ID: " + id);
     * }
     *
     * // Example in a multi-threaded environment
     * ExecutorService executor = Executors.newFixedThreadPool(Runtime.getRuntime().availableProcessors());
     * List<Future<Long>> futures = new ArrayList<>();
     * for (int i = 0; i < 100; i++) {
     *     futures.add(executor.submit(userSequence::nextVal));
     * }
     * // Collect and verify unique IDs
     * Set<Long> uniqueIds = new HashSet<>();
     * for (Future<Long> future : futures) {
     *     uniqueIds.add(future.get()); // .get() might throw exceptions
     * }
     * System.out.println("Generated " + uniqueIds.size() + " unique IDs concurrently.");
     * executor.shutdown();
     * }</pre>
     *
     * @return the next unique {@code long} value from the sequence.
     * @throws UncheckedSQLException if a database access error occurs during the fetch of a new sequence block,
     *         or if the sequence cannot be acquired after maximum retries due to persistent contention.
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
     * Resets the sequence to a new starting value and configures a new buffer size.
     *
     * <p>This method updates both the database record for this sequence and the in-memory
     * state of the {@code DBSequence} instance. All currently buffered sequence values
     * are discarded. The next call to {@link #nextVal()} will return the new {@code startVal}.
     * This operation is synchronized to ensure thread safety during the reset process.</p>
     *
     * <p><b>Important Warning:</b> Resetting a sequence to a value lower than any previously
     * generated value can lead to the generation of duplicate IDs, which can cause data
     * integrity issues. Exercise extreme caution when using this method in production
     * environments. It is generally safer to create a new sequence with a different name
     * or to only reset to a higher starting value.</p>
     *
     * <p><b>Use Cases:</b></p>
     * <ul>
     *   <li><b>Testing:</b> To re-initialize sequence states between test runs.</li>
     *   <li><b>Migration:</b> To adjust sequence starting points after data migrations.</li>
     *   <li><b>Buffer Adjustment:</b> To dynamically change the {@code seqBufferSize} based on observed usage patterns.</li>
     *   <li><b>Conflict Avoidance:</b> To set a higher starting value to prevent potential overlaps with other ID ranges.</li>
     * </ul>
     *
     * <p><b>Usage Examples:</b></p>
     * <pre>{@code
     * DBSequence mySequence = new DBSequence(dataSource, "app_sequences", "my_custom_id", 1, 10);
     *
     * // Generate some IDs
     * long id1 = mySequence.nextVal(); // Returns 1
     * long id2 = mySequence.nextVal(); // Returns 2
     *
     * // Reset the sequence to start from 1000 with a larger buffer of 100
     * mySequence.reset(1000, 100);
     * long id3 = mySequence.nextVal(); // Returns 1000
     *
     * // Resetting to a value based on the last generated ID plus a new buffer size
     * long lastGeneratedId = mySequence.nextVal(); // Assuming this is 1001
     * mySequence.reset(lastGeneratedId + 1, 200); // Next ID will be 1002, new buffer size 200
     * }</pre>
     *
     * @param startVal the new starting value for the sequence. Must be non-negative.
     * @param seqBufferSize the new number of sequence values to pre-fetch and cache in memory. Must be greater than 0.
     * @throws UncheckedSQLException if a database access error occurs during the update of the sequence record.
     * @throws IllegalArgumentException if {@code startVal} is negative or {@code seqBufferSize} is not positive.
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