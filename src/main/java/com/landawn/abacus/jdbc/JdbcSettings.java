/*
 * Copyright (c) 2021, Haiyang Li.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.landawn.abacus.jdbc;

import java.sql.ResultSet;
import java.sql.Statement;

import com.landawn.abacus.util.N;

import lombok.AccessLevel;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.Setter;
import lombok.ToString;
import lombok.experimental.Accessors;

@Data
@Accessors(chain = true)
public final class JdbcSettings {

    public static final String DEFAULT_GENERATED_ID_PROP_NAME = "id";

    public static final int DEFAULT_BATCH_SIZE = JdbcUtil.DEFAULT_BATCH_SIZE;

    public static final int DEFAULT_NO_GENERATED_KEYS = Statement.NO_GENERATED_KEYS;

    public static final int DEFAULT_FETCH_DIRECTION = ResultSet.FETCH_FORWARD;

    public static final int DEFAULT_RESULT_SET_TYPE = ResultSet.TYPE_FORWARD_ONLY;

    public static final int DEFAULT_RESULT_SET_CONCURRENCY = ResultSet.CONCUR_READ_ONLY;

    public static final int DEFAULT_RESULT_SET_HOLDABILITY = ResultSet.HOLD_CURSORS_OVER_COMMIT;

    private boolean logSQL = false;

    private int batchSize = -1;

    private int queryTimeout = -1;

    private boolean autoGeneratedKeys = false;

    private int[] returnedColumnIndexes = null;

    private String[] returnedColumnNames = null;

    private int maxRows = -1;

    private int maxFieldSize = -1;

    private int fetchSize = -1;

    private int fetchDirection = -1;

    private int resultSetType = -1;

    private int resultSetConcurrency = -1;

    private int resultSetHoldability = -1;

    private boolean queryInParallel = false;

    private IsolationLevel isolationLevel = null;

    @Getter(value = AccessLevel.NONE)
    @Setter(value = AccessLevel.NONE)
    @EqualsAndHashCode.Exclude
    @ToString.Exclude
    private boolean noTransactionForStream = false;

    @Getter(value = AccessLevel.NONE)
    @Setter(value = AccessLevel.NONE)
    @EqualsAndHashCode.Exclude
    @ToString.Exclude
    private boolean fozen = false;

    /**
     *
     * @return
     */
    public static JdbcSettings create() {
        return new JdbcSettings();
    }

    /**
     *
     * @return
     */
    public JdbcSettings copy() {
        JdbcSettings copy = new JdbcSettings();
        copy.logSQL = this.logSQL;
        copy.batchSize = this.batchSize;
        copy.queryTimeout = this.queryTimeout;
        copy.autoGeneratedKeys = this.autoGeneratedKeys;
        copy.returnedColumnIndexes = (this.returnedColumnIndexes == null) ? null : N.copyOf(this.returnedColumnIndexes, this.returnedColumnIndexes.length);
        copy.returnedColumnNames = (this.returnedColumnNames == null) ? null : N.copyOf(this.returnedColumnNames, this.returnedColumnNames.length);
        copy.maxRows = this.maxRows;
        copy.maxFieldSize = this.maxFieldSize;
        copy.fetchSize = this.fetchSize;
        copy.fetchDirection = this.fetchDirection;
        copy.resultSetType = this.resultSetType;
        copy.resultSetConcurrency = this.resultSetConcurrency;
        copy.resultSetHoldability = this.resultSetHoldability;
        copy.queryInParallel = this.queryInParallel;
        copy.isolationLevel = this.isolationLevel;
        copy.noTransactionForStream = this.noTransactionForStream;

        return copy;
    }

    /**
     * Stream transaction independent.
     *
     * @return true, if successful
     */
    boolean noTransactionForStream() {
        return noTransactionForStream;
    }

    /**
     * {@code noTransactionForStream = true} means the query executed by {@code stream/streamAll(...)} methods won't be in any transaction(using connection started by transaction), even the {@code stream/streamAll(...)} methods are invoked inside of a transaction block.
     *
     * @param noTransactionForStream
     * @return
     */
    JdbcSettings setNoTransactionForStream(final boolean noTransactionForStream) {
        assertNotFrozen();

        this.noTransactionForStream = noTransactionForStream;

        return this;
    }

    /**
     * Freeze.
     */
    void freeze() {
        fozen = true;
    }

    /**
     * Assert not frozen.
     */
    void assertNotFrozen() {
        if (fozen) {
            throw new RuntimeException("It's finalized. No change is allowed");
        }
    }
}