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

import com.landawn.abacus.exception.UncheckedSQLException;

// TODO: Auto-generated Javadoc
/**
 *
 */
public interface Transaction {

    /**
     * Returns the unique identifier of the transaction.
     *
     * @return the unique identifier of the transaction.
     */
    String id();

    /**
     * Returns the isolation level of the transaction.
     *
     * @return the isolation level of the transaction.
     */
    IsolationLevel isolationLevel();

    /**
     * Returns the current status of the transaction.
     *
     * @return the current status of the transaction.
     */
    Status status();

    /**
     * Checks if the transaction is active.
     *
     * @return {@code true} if the transaction is active, {@code false} otherwise.
     */
    boolean isActive();

    /**
     * Commits the current transaction.
     *
     * @throws UncheckedSQLException if an SQL error occurs during the commit.
     */
    void commit() throws UncheckedSQLException;

    //    /**
    //     * Commits the current transaction and executes the specified action after the commit.
    //     *
    //     * @param actionAfterCommit the action to be executed after the current transaction is committed successfully.
    //     * @throws UncheckedSQLException if an SQL error occurs during the commit.
    //     */
    //    @Beta
    //    void commit(Runnable actionAfterCommit) throws UncheckedSQLException;

    /**
     * Rolls back the current transaction.
     *
     * @throws UncheckedSQLException the unchecked SQL exception
     */
    void rollback() throws UncheckedSQLException;

    //    /**
    //     * Rolls back the current transaction and execute the specified action after the rollback.
    //     *
    //     * @param actionAfterRollback the action to be executed after the current transaction is rolled back, not successfully or not.
    //     * @throws UncheckedSQLException if an SQL error occurs during the rollback.
    //     */
    //    @Beta
    //    void rollback(Runnable actionAfterRollback) throws UncheckedSQLException;

    /**
     * Rolls back the transaction if it has not been committed successfully.
     *
     * @throws UncheckedSQLException if an SQL error occurs during the rollback.
     */
    void rollbackIfNotCommitted() throws UncheckedSQLException;

    /**
     * The Enum Status.
     *
     * @version $Revision: 0.8 $ 07/01/15
     */
    enum Status {
        /**
         * Field ACTIVE.
         */
        ACTIVE,
        /**
         * Field MARKED_ROLLBACK.
         */
        MARKED_ROLLBACK,
        /**
         * Field COMMITTED.
         */
        COMMITTED,
        /**
         * Field FAILED_COMMIT.
         */
        FAILED_COMMIT,
        /**
         * Field ROLLED_BACK.
         */
        ROLLED_BACK,
        /**
         * Field FAILED_ROLLBACK.
         */
        FAILED_ROLLBACK
    }

    /**
     * The Enum Action.
     *
     */
    enum Action {

        /** The commit. */
        COMMIT,

        /** The rollback. */
        ROLLBACK
    }
}
