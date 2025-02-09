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
import com.landawn.abacus.util.DateUtil;
import com.landawn.abacus.util.Strings;

// TODO: Auto-generated Javadoc
/**
 * Supports global sequence by db table.
 *
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
            throw new IllegalArgumentException("startVal must be greater than 0");
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

            final Timestamp now = DateUtil.currentTimestamp();

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
     * This method is synchronized on the sequence name to ensure thread safety.
     * If the current low sequence ID is greater than or equal to the high sequence ID,
     * it fetches the next batch of sequence IDs from the database.
     *
     * @return The next value in the sequence.
     * @throws UncheckedSQLException if a database access error occurs.
     */
    public long nextVal() {
        synchronized (seqName) { //NOSONAR
            try {
                while (lowSeqId.get() >= highSeqId.get()) {
                    //noinspection resource
                    lowSeqId.set(JdbcUtil.prepareQuery(ds, querySQL).setString(1, seqName).queryForLong().orElse(0));

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
     * @param startVal The new starting value for the sequence.
     * @param seqBufferSize The new buffer size for the sequence.
     */
    @SuppressWarnings("hiding")
    public void reset(final long startVal, final int seqBufferSize) {
        this.seqBufferSize = seqBufferSize;

        try {
            JdbcUtil.executeUpdate(ds, resetSQL, startVal, DateUtil.currentTimestamp(), seqName);
        } catch (final SQLException e) {
            throw new UncheckedSQLException(e);
        }
    }
}
