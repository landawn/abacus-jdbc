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

/**
 * Represents a database transaction that provides methods for managing transactional operations.
 * This interface abstracts the transaction management layer, allowing for consistent transaction
 * handling across different database implementations.
 * 
 * <p>A transaction follows the ACID properties (Atomicity, Consistency, Isolation, Durability)
 * and provides methods to commit or rollback changes made within the transaction scope.</p>
 * 
 * <p><b>Usage Examples:</b></p>
 * <pre>{@code
 * Transaction txn = JdbcUtil.beginTransaction(dataSource);
 * try {
 *     // Perform database operations
 *     txn.commit();
 * } finally {
 *     txn.rollbackIfNotCommitted();
 * }
 * }</pre>
 *
 * @see IsolationLevel
 * @see Status
 */
public interface Transaction {

    /**
     * Returns the unique identifier of this transaction.
     * The identifier is typically generated when the transaction is created and remains
     * constant throughout the transaction's lifecycle.
     * 
     * <p>This ID can be useful for logging, debugging, and tracking transaction flow
     * in distributed systems.</p>
     * 
     * <p><b>Usage Examples:</b></p>
     * <pre>{@code
     * String transactionId = transaction.id();
     * logger.info("Starting transaction: " + transactionId);
     * }</pre>
     *
     * @return the unique identifier of the transaction, never {@code null}
     */
    String id();

    /**
     * Returns the isolation level of this transaction.
     * The isolation level determines how transaction integrity is visible to other users
     * and systems, controlling the trade-off between performance and consistency.
     * 
     * <p>Common isolation levels include:</p>
     * <ul>
     *   <li>READ_UNCOMMITTED - Allows dirty reads</li>
     *   <li>READ_COMMITTED - Prevents dirty reads</li>
     *   <li>REPEATABLE_READ - Prevents dirty and non-repeatable reads</li>
     *   <li>SERIALIZABLE - Prevents all phenomena</li>
     * </ul>
     * 
     * <p><b>Usage Examples:</b></p>
     * <pre>{@code
     * IsolationLevel level = transaction.isolationLevel();
     * if (level == IsolationLevel.SERIALIZABLE) {
     *     // Handle high isolation scenario
     * }
     * }</pre>
     *
     * @return the isolation level of the transaction, never {@code null}
     */
    IsolationLevel isolationLevel();

    /**
     * Returns the current status of this transaction.
     * The status indicates the current state in the transaction lifecycle,
     * such as whether it's active, committed, or rolled back.
     * 
     * <p><b>Usage Examples:</b></p>
     * <pre>{@code
     * Status status = transaction.status();
     * if (status == Status.ACTIVE) {
     *     // Transaction is still active and can be committed or rolled back
     * }
     * }</pre>
     *
     * @return the current status of the transaction, never {@code null}
     * @see Status
     */
    Status status();

    /**
     * Checks if this transaction is currently active.
     * A transaction is considered active if it has been started but not yet
     * committed or rolled back.
     * 
     * <p>This is a convenience method equivalent to checking if the status
     * equals {@link Status#ACTIVE}.</p>
     * 
     * <p><b>Usage Examples:</b></p>
     * <pre>{@code
     * if (transaction.isActive()) {
     *     // Safe to perform operations within this transaction
     *     performDatabaseOperations();
     * }
     * }</pre>
     *
     * @return {@code true} if the transaction is active, {@code false} otherwise
     */
    boolean isActive();

    /**
     * Commits the current transaction, making all changes permanent.
     * After a successful commit, the transaction is no longer active and cannot
     * be used for further operations.
     * 
     * <p>If the commit fails, the transaction status will be set to
     * {@link Status#FAILED_COMMIT} and an exception will be thrown.</p>
     * 
     * <p><b>Usage Examples:</b></p>
     * <pre>{@code
     * try {
     *     // Perform database operations
     *     updateRecords();
     *     transaction.commit();
     *     logger.info("Transaction committed successfully");
     * } catch (UncheckedSQLException e) {
     *     logger.error("Failed to commit transaction", e);
     *     // Handle commit failure
     * }
     * }</pre>
     *
     * @throws UncheckedSQLException if an SQL error occurs during the commit,
     *         or if the transaction is not in an active state
     * @throws IllegalStateException if the transaction has already been committed or rolled back
     */
    void commit() throws UncheckedSQLException;

    /**
     * Rolls back the current transaction, undoing all changes made within
     * the transaction scope. After a successful rollback, the transaction
     * is no longer active and cannot be used for further operations.
     * 
     * <p>If the rollback fails, the transaction status will be set to
     * {@link Status#FAILED_ROLLBACK} and an exception will be thrown.</p>
     * 
     * <p><b>Usage Examples:</b></p>
     * <pre>{@code
     * try {
     *     performRiskyOperation();
     * } catch (Exception e) {
     *     transaction.rollback();
     *     logger.warn("Transaction rolled back due to: " + e.getMessage());
     * }
     * }</pre>
     *
     * @throws UncheckedSQLException if an SQL error occurs during the rollback,
     *         or if the transaction is not in an active state
     * @throws IllegalStateException if the transaction has already been committed or rolled back
     */
    void rollback() throws UncheckedSQLException;

    /**
     * Rolls back the transaction if it has not been committed successfully.
     * This method is safe to call multiple times and will only perform a rollback
     * if the transaction is still active or in a failed commit state.
     * 
     * <p>This method is particularly useful in finally blocks or cleanup code
     * where you want to ensure a transaction is not left in an active state.</p>
     * 
     * <p>The method will attempt rollback in the following states:</p>
     * <ul>
     *   <li>{@link Status#ACTIVE} - Normal rollback</li>
     *   <li>{@link Status#MARKED_ROLLBACK} - Completes the rollback</li>
     *   <li>{@link Status#FAILED_COMMIT} - Attempts to rollback after failed commit</li>
     * </ul>
     * 
     * <p><b>Usage Examples:</b></p>
     * <pre>{@code
     * Transaction txn = JdbcUtil.beginTransaction(dataSource);
     * try {
     *     performDatabaseOperations();
     *     txn.commit();
     * } finally {
     *     txn.rollbackIfNotCommitted();   // Ensures cleanup
     * }
     * }</pre>
     *
     * @throws UncheckedSQLException if an SQL error occurs during the rollback attempt
     */
    void rollbackIfNotCommitted() throws UncheckedSQLException;

    /**
     * Enumeration representing the various states a transaction can be in during its lifecycle.
     * The status transitions typically follow this pattern:
     * <pre>
     * ACTIVE → COMMITTED (on successful commit)
     *        → ROLLED_BACK (on successful rollback)
     *        → MARKED_ROLLBACK → ROLLED_BACK (on rollback-only mode)
     *        → FAILED_COMMIT (on commit failure)
     *        → FAILED_ROLLBACK (on rollback failure)
     * </pre>
     *
     */
    enum Status {
        /**
         * The transaction is active and can accept operations.
         * This is the initial state when a transaction is created.
         * In this state, database operations can be performed, and the
         * transaction can be either committed or rolled back.
         */
        ACTIVE,

        /**
         * The transaction has been marked for rollback only.
         * This state indicates that the transaction cannot be committed
         * and must be rolled back. This typically occurs when an error
         * is detected that requires the transaction to be aborted.
         */
        MARKED_ROLLBACK,

        /**
         * The transaction has been successfully committed.
         * All changes made within the transaction have been permanently
         * saved to the database. The transaction is complete and cannot
         * be used for further operations.
         */
        COMMITTED,

        /**
         * The transaction commit operation failed.
         * This state indicates that an attempt to commit the transaction
         * encountered an error. The transaction may need to be rolled back
         * to clean up any partial changes.
         */
        FAILED_COMMIT,

        /**
         * The transaction has been successfully rolled back.
         * All changes made within the transaction have been undone.
         * The transaction is complete and cannot be used for further operations.
         */
        ROLLED_BACK,

        /**
         * The transaction rollback operation failed.
         * This is a critical state indicating that the attempt to undo
         * the transaction changes was unsuccessful. Manual intervention
         * may be required to restore database consistency.
         */
        FAILED_ROLLBACK
    }

    /**
     * Enumeration representing the possible actions that can be performed on a transaction.
     * This enum is typically used in transaction management frameworks to specify
     * the desired outcome of a transactional operation.
     *
     * <p><b>Usage Examples:</b></p>
     * <pre>{@code
     * Action action = shouldCommit ? Action.COMMIT : Action.ROLLBACK;
     * transactionManager.completeTransaction(transaction, action);
     * }</pre>
     *
     */
    enum Action {
        /**
         * Indicates that the transaction should be committed.
         * When this action is specified, all changes made within the
         * transaction scope will be permanently saved to the database.
         */
        COMMIT,

        /**
         * Indicates that the transaction should be rolled back.
         * When this action is specified, all changes made within the
         * transaction scope will be undone and the database will be
         * restored to its state before the transaction began.
         */
        ROLLBACK
    }
}
