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
 * Minimal contract for a JDBC transaction managed by this library.
 *
 * <p>A {@code Transaction} exposes an identifier, the effective {@link IsolationLevel},
 * lifecycle state, and explicit commit or rollback operations.</p>
 *
 * <p>The built-in implementation is {@link SqlTransaction}, obtained from
 * {@link JdbcUtil#beginTransaction(javax.sql.DataSource)} or one of its overloads. Its class documentation describes how
 * transactions work in this library: a transaction is bound to the thread that started it and to its data source, and
 * queries and DAO calls on that data source on that thread enlist in it automatically; beginning a transaction again on the
 * same thread and data source joins it as a nested scope, and only the outermost scope commits or rolls back the database
 * transaction; inside a Spring-managed transaction that owns the data source's connection, the transaction participates in
 * the Spring transaction instead of committing or rolling back its connection.</p>
 *
 * <p>An implementation may bind a transaction to the thread that started it, in which case the
 * lifecycle methods below throw {@link IllegalStateException} when they are invoked from any other
 * thread — {@link SqlTransaction}, the built-in implementation, does exactly that.</p>
 *
 * <p><b>Usage Examples:</b></p>
 * <pre>{@code
 * try (SqlTransaction tran = JdbcUtil.beginTransaction(dataSource)) {
 *     orderDao.insert(order);
 *     JdbcUtil.executeUpdate(dataSource, "UPDATE inventory SET quantity = quantity - ? WHERE product_id = ?", quantity, productId);
 *     tran.commit();
 * } // rolled back if commit() was not reached
 * }</pre>
 *
 * @see SqlTransaction
 * @see JdbcUtil#beginTransaction(javax.sql.DataSource)
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
     * logger.info("Starting transaction: {}", transactionId);
     * }</pre>
     *
     * @return the unique identifier of the transaction, never {@code null}
     */
    String id();

    /**
     * Returns the isolation level of this transaction.
     * The isolation level determines how and when changes made by this transaction become
     * visible to other concurrent transactions, controlling the trade-off between performance
     * and consistency.
     *
     * <p>Common isolation levels include:</p>
     * <ul>
     *   <li>{@code READ_UNCOMMITTED} - allows dirty reads</li>
     *   <li>{@code READ_COMMITTED} - prevents dirty reads</li>
     *   <li>{@code REPEATABLE_READ} - prevents dirty and non-repeatable reads</li>
     *   <li>{@code SERIALIZABLE} - prevents dirty reads, non-repeatable reads, and phantom reads</li>
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
     * A transaction is considered active only while its status is {@link Status#ACTIVE}.
     * A transaction marked for rollback or reporting a failed commit or rollback is not active.
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
     * For implementations with nested scopes, such as {@link SqlTransaction}, an inner commit
     * only completes that scope; the enclosing scope still controls the database commit.
     *
     * <p>If the commit fails, the transaction passes through {@link Status#FAILED_COMMIT}
     * and an exception is thrown. Implementations may additionally attempt an automatic
     * rollback after a failed commit ({@link SqlTransaction} does, so the caller sees {@link Status#ROLLED_BACK} or
     * {@link Status#FAILED_ROLLBACK}, unless it participates in a Spring-managed transaction, where
     * {@link Status#FAILED_COMMIT} is final). A transaction marked rollback-only
     * ({@link Status#MARKED_ROLLBACK}) is rolled back instead of committed; {@link SqlTransaction} then throws
     * {@link RollbackOnlyException}.</p>
     *
     * <p>A {@link SqlTransaction} that participates in a Spring-managed transaction issues no database commit:
     * the commit completes its scope, and Spring commits the work when its own transaction completes.</p>
     *
     * <p><b>Usage Examples:</b></p>
     * <pre>{@code
     * try {
     *     updateRecords();
     *     transaction.commit();
     * } finally {
     *     // Also rolls back when the work, rather than commit itself, fails.
     *     transaction.rollbackIfNotCommitted();
     * }
     * }</pre>
     *
     * @throws IllegalStateException if called from a thread other than the transaction owner, or if the transaction is not in a valid state for
     *         committing. Note: {@link SqlTransaction} (the built-in implementation) throws for a scope that has completed: commit() may
     *         be called only once per scope, also after a successful one, and a scope that ended without a commit (rolled back, its commit
     *         failed, or exited by an enclosing scope) can't commit its work; it throws {@link RollbackOnlyException} (an
     *         {@code IllegalStateException}) when the outermost scope finds the transaction marked rollback-only and rolls it back.
     * @throws UncheckedSQLException if an SQL error occurs during commit, rollback of a rollback-only transaction, or restoration of a nested scope's
     *         isolation level
     */
    void commit() throws IllegalStateException, UncheckedSQLException;

    /**
     * Rolls back the current transaction, undoing all changes made within
     * the transaction scope. After a successful rollback, the transaction
     * is no longer active and cannot be used for further operations.
     * For nested scopes in {@link SqlTransaction}, an inner rollback instead marks the shared
     * transaction rollback-only; the database rollback occurs when the outermost scope completes.
     *
     * <p>If the rollback fails, the transaction status will be set to
     * {@link Status#FAILED_ROLLBACK} and an exception will be thrown.</p>
     *
     * <p>A {@link SqlTransaction} that participates in a Spring-managed transaction issues no database rollback:
     * it marks the Spring transaction rollback-only, so Spring rolls the work back when its transaction completes.</p>
     *
     * <p><b>Usage Examples:</b></p>
     * <pre>{@code
     * try {
     *     performRiskyOperation();
     *
     *     if (!isStillWanted()) {
     *         transaction.rollback(); // only on a scope that has not completed
     *         return;
     *     }
     *
     *     transaction.commit();
     * } finally {
     *     // Rolls back if the work or the commit failed; a no-op after the rollback() or commit() above.
     *     transaction.rollbackIfNotCommitted();
     * }
     * }</pre>
     *
     * <p>Don't call it after a failed {@code commit()}: the commit completes the scope also when it fails, so
     * {@link SqlTransaction} throws {@link IllegalStateException} there, and a {@code rollback()} in a {@code catch} block hid
     * the commit's own failure.</p>
     *
     * <p>Note: the {@link SqlTransaction} implementation deprecates direct calls to this method
     * in favor of {@link #rollbackIfNotCommitted()}.</p>
     *
     * @throws IllegalStateException if called from a thread other than the transaction owner, or if the transaction is not in a valid state for
     *         rollback. Note: {@link SqlTransaction} (the built-in implementation) throws it for a scope that has already completed,
     *         as Spring does (it used to log and ignore the call); its {@code rollbackIfNotCommitted()} and {@code close()}
     *         stay no-ops on a completed scope.
     * @throws UncheckedSQLException if an SQL error occurs during the rollback (an implementation may also report a failure to restore a nested
     *         scope's isolation level; {@link SqlTransaction} logs it, and exits the scope all the same)
     */
    void rollback() throws IllegalStateException, UncheckedSQLException;

    /**
     * Rolls back the transaction if it has not been committed successfully.
     * Once the transaction has completed (committed or rolled back), further calls are no-ops;
     * a rollback is only performed while the transaction is still active, marked for rollback,
     * or in a failed-commit state. For implementations with nested scopes, such as {@link SqlTransaction}
     * (where every {@code beginTransaction} call returns its own scope handle), the call ends the scope it is
     * invoked on: a no-op once that scope has committed or rolled back, and on any further call.
     *
     * <p>This method is particularly useful in finally blocks or cleanup code
     * where you want to ensure a transaction is not left in an active state.</p>
     *
     * <p>The method will attempt rollback in the following states:</p>
     * <ul>
     *   <li>{@link Status#ACTIVE} - Normal rollback</li>
     *   <li>{@link Status#MARKED_ROLLBACK} - Completes the rollback</li>
     *   <li>{@link Status#FAILED_COMMIT} - Attempts to rollback after failed commit (an implementation that rolls back
     *       automatically after a failed commit, as {@link SqlTransaction} does, has completed by then: the call is a
     *       no-op)</li>
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
     * @throws IllegalStateException if called from a thread other than the transaction owner, or if the transaction is in an unexpected status (other
     *         than {@link Status#ACTIVE}, {@link Status#MARKED_ROLLBACK}, or {@link Status#FAILED_COMMIT}) when the rollback is actually performed
     *         ({@link SqlTransaction}: if the rollback of a transaction participating in a Spring-managed one can't mark it rollback-only)
     * @throws UncheckedSQLException if an SQL error occurs during the rollback (an implementation may also report a failure to restore a nested
     *         scope's isolation level; {@link SqlTransaction} logs it, and exits the scope all the same)
     */
    void rollbackIfNotCommitted() throws IllegalStateException, UncheckedSQLException;

    /**
     * Enumeration representing the various states a transaction can be in during its lifecycle.
     * The status transitions typically follow this pattern:
     * <pre>
     * ACTIVE → COMMITTED (on successful commit)
     *        → ROLLED_BACK (on successful rollback)
     *        → MARKED_ROLLBACK → ROLLED_BACK / FAILED_ROLLBACK (on rollback-only mode)
     *        → FAILED_COMMIT (on commit failure)
     *        → FAILED_ROLLBACK (on rollback failure)
     * FAILED_COMMIT → ROLLED_BACK / FAILED_ROLLBACK (on automatic rollback after a failed commit)
     * </pre>
     *
     * <p>A {@link SqlTransaction} participating in a Spring-managed transaction ends {@code FAILED_COMMIT} for good, with no
     * rollback, when the Spring transaction has completed before its outermost commit (see {@link #FAILED_COMMIT}).</p>
     *
     * <p>For a {@link SqlTransaction} with nested scopes, the status changes from {@code ACTIVE} only when the transaction is
     * marked rollback-only ({@code MARKED_ROLLBACK}: a nested scope rolled back or was never completed, or its isolation level
     * could not be restored) or when the outermost scope completes. For one that participates in a
     * Spring-managed transaction, {@code COMMITTED} and {@code ROLLED_BACK} describe the completion of its scope (the
     * Spring transaction decides the outcome of the work), {@code FAILED_ROLLBACK} means that the Spring transaction
     * could not be marked rollback-only, and {@code FAILED_COMMIT} that it had completed (or begun to complete) before the
     * outermost commit, deciding the outcome of the work without it.</p>
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
         * and must be rolled back. For {@link SqlTransaction}, it occurs when a nested
         * scope rolls back (for example, a nested scope whose work threw an exception): the
         * outermost scope then rolls back even if it calls {@link Transaction#commit()}, which throws
         * {@link RollbackOnlyException}. Scopes begun meanwhile join the transaction, and their work is
         * rolled back with it.
         */
        MARKED_ROLLBACK,

        /**
         * The transaction has been successfully committed.
         * All changes made within the transaction have been permanently
         * saved to the database. The transaction is complete and cannot
         * be used for further operations. For a {@link SqlTransaction} that participates in a
         * Spring-managed transaction, its scope has completed and Spring commits the work when its
         * own transaction completes.
         */
        COMMITTED,

        /**
         * The transaction commit operation failed.
         * This state indicates that an attempt to commit the transaction
         * encountered an error. The transaction may need to be rolled back
         * to clean up any partial changes; {@link SqlTransaction} rolls it back automatically,
         * so this state is followed by {@link #ROLLED_BACK} or {@link #FAILED_ROLLBACK}. For a {@link SqlTransaction}
         * that participates in a Spring-managed transaction, the state is final: the Spring transaction had completed (or
         * begun to complete, in its completion callbacks) before the outermost commit, and decided the outcome of the
         * work without it, so nothing is rolled back.
         */
        FAILED_COMMIT,

        /**
         * The transaction has been successfully rolled back.
         * All changes made within the transaction have been undone.
         * The transaction is complete and cannot be used for further operations.
         * For a {@link SqlTransaction} that participates in a Spring-managed transaction, the Spring
         * transaction has been marked rollback-only, and Spring rolls the work back when its
         * transaction completes.
         */
        ROLLED_BACK,

        /**
         * The transaction rollback operation failed.
         * This is a critical state indicating that the attempt to undo
         * the transaction changes was unsuccessful. Manual intervention
         * may be required to restore database consistency. For a {@link SqlTransaction} that
         * participates in a Spring-managed transaction, the Spring transaction could not be marked
         * rollback-only, so Spring may still commit the work.
         */
        FAILED_ROLLBACK
    }
}
