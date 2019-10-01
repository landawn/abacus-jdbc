/*
 * Copyright (C) 2015 HaiYang Li
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file except
 * in compliance with the License. You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software distributed under the License
 * is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express
 * or implied. See the License for the specific language governing permissions and limitations under
 * the License.
 */

package com.landawn.abacus.util;

import java.sql.Timestamp;
import java.util.concurrent.atomic.AtomicLong;

import com.landawn.abacus.exception.AbacusException;
import com.landawn.abacus.logging.Logger;
import com.landawn.abacus.logging.LoggerFactory;

// TODO: Auto-generated Javadoc
/**
 * Supports global sequence by db table.
 *
 * @author Haiyang Li
 * @since 0.8
 */
public final class DBSequence {

    /** The Constant logger. */
    private static final Logger logger = LoggerFactory.getLogger(DBSequence.class);

    /** The sql executor. */
    private final SQLExecutor sqlExecutor;

    /** The seq name. */
    private final String seqName;

    /** The seq buffer size. */
    private int seqBufferSize;

    /** The query SQL. */
    private final String querySQL;

    /** The update SQL. */
    private final String updateSQL;

    /** The reset SQL. */
    private final String resetSQL;

    /** The low seq id. */
    private final AtomicLong lowSeqId;

    /** The high seq id. */
    private final AtomicLong highSeqId;

    /**
     * Instantiates a new DB sequence.
     *
     * @param sqlExecutor
     * @param tableName
     * @param seqName
     * @param startVal
     * @param seqBufferSize
     */
    DBSequence(SQLExecutor sqlExecutor, String tableName, String seqName, long startVal, int seqBufferSize) {
        this.sqlExecutor = sqlExecutor;
        this.seqName = seqName;
        this.seqBufferSize = seqBufferSize;

        if (N.isNullOrEmpty(tableName)) {
            throw new IllegalArgumentException("Table name can't be null or empty");
        }

        if (N.isNullOrEmpty(seqName)) {
            throw new IllegalArgumentException("Sequence name can't be null or empty");
        }

        if (startVal < 0) {
            throw new IllegalArgumentException("startVal can't be negative");
        }

        if (seqBufferSize <= 0) {
            throw new IllegalArgumentException("startVal must be greater than 0");
        }

        querySQL = "SELECT next_val FROM " + tableName + " WHERE seq_name = ?";
        updateSQL = "UPDATE " + tableName + " SET next_val = ?, update_time = ? WHERE next_val = ? AND seq_name = ?";
        resetSQL = "UPDATE " + tableName + " SET next_val = ?, update_time = ? WHERE seq_name = ?";
        lowSeqId = new AtomicLong(startVal);
        highSeqId = new AtomicLong(startVal);

        String schema = "CREATE TABLE " + tableName
                + "(seq_name VARCHAR(64), next_val BIGINT, update_time TIMESTAMP NOT NULL, create_time TIMESTAMP NOT NULL, UNIQUE (seq_name))";

        if (!sqlExecutor.doesTableExist(tableName)) {
            try {
                sqlExecutor.createTableIfNotExists(tableName, schema);
            } catch (Exception e) {
                if (logger.isWarnEnabled()) {
                    logger.warn("Failed to create table: " + tableName);
                }
            }

            if (!sqlExecutor.doesTableExist(tableName)) {
                throw new AbacusException("Failed to create table: " + tableName);
            }
        }

        Timestamp now = DateUtil.currentTimestamp();

        if (sqlExecutor.queryForInt("SELECT 1 FROM " + tableName + " WHERE seq_name = ?", seqName).orElse(0) < 1) {
            try {
                sqlExecutor.update("INSERT INTO " + tableName + "(seq_name, next_val, update_time, create_time) VALUES (?, ?, ?, ?)", seqName, startVal, now,
                        now);
            } catch (Exception e) {
                if (logger.isWarnEnabled()) {
                    logger.warn("Failed to initialize sequence: " + seqName + " within table: " + tableName);
                }
            }
        }

        sqlExecutor.update("UPDATE " + tableName + " SET next_val = ?, update_time = ? WHERE seq_name = ? AND next_val < ?", startVal, now, seqName, startVal);

        if (sqlExecutor.queryForLong("SELECT next_val FROM " + tableName + " WHERE seq_name = ?", seqName).orElse(0) < startVal) {
            throw new AbacusException("Failed to initialize sequence: " + seqName + " within table: " + tableName);
        }
    }

    /**
     *
     * @return
     */
    public long nextVal() {
        synchronized (seqName) {
            while (lowSeqId.get() >= highSeqId.get()) {
                lowSeqId.set(sqlExecutor.queryForLong(querySQL, seqName).orElse(0));

                if (sqlExecutor.update(updateSQL, lowSeqId.get() + seqBufferSize, DateUtil.currentTimestamp(), lowSeqId.get(), seqName) > 0) {
                    highSeqId.set(lowSeqId.get() + seqBufferSize);

                    break;
                }
            }
        }

        return lowSeqId.getAndIncrement();
    }

    /**
     *
     * @param startVal
     * @param seqBufferSize
     */
    public void reset(long startVal, int seqBufferSize) {
        this.seqBufferSize = seqBufferSize;

        sqlExecutor.update(resetSQL, startVal, DateUtil.currentTimestamp(), seqName);
    }
}
