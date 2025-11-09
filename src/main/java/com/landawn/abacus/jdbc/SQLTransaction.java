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
import java.util.Deque;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.concurrent.atomic.AtomicInteger;

import com.landawn.abacus.exception.UncheckedSQLException;
import com.landawn.abacus.logging.Logger;
import com.landawn.abacus.logging.LoggerFactory;
import com.landawn.abacus.util.Fn;
import com.landawn.abacus.util.N;
import com.landawn.abacus.util.Strings;
import com.landawn.abacus.util.Throwables;

/**
 * Represents a SQL transaction that manages database transaction lifecycle and connection state.
 * This class provides transaction management capabilities including commit, rollback, and automatic
 * resource cleanup. It supports nested transactions with reference counting and isolation level management.
 * 
 * <p><b>Important:</b> DO NOT CLOSE the connection manually. It will be automatically closed 
 * after the transaction is committed or rolled back.</p>
 * 
 * <p><b>Usage Examples:</b></p>
 * <pre>{@code
 * try (SQLTransaction tran = JdbcUtil.beginTransaction(dataSource, IsolationLevel.READ_COMMITTED)) {
 *     // Execute database operations
 *     dao.insert(entity);
 *     dao.update(anotherEntity);
 *     
 *     tran.commit(); // Commit the transaction
 * } // Auto-rollback if not committed
 * }</pre>
 * 
 * 
 * @see Transaction
 * @see JdbcUtil#beginTransaction(javax.sql.DataSource)
 * @see JdbcUtil#beginTransaction(javax.sql.DataSource, IsolationLevel)
 */
@SuppressWarnings("resource")
public final class SQLTransaction implements Transaction, AutoCloseable {

    private static final Logger logger = LoggerFactory.getLogger(SQLTransaction.class);

    private static final Map<String, SQLTransaction> threadTransactionMap = new ConcurrentHashMap<>();
    // private static final Map<String, SQLTransaction> attachedThreadTransactionMap = new ConcurrentHashMap<>();

    private final String _id; //NOSONAR

    private final String _timedId; //NOSONAR

    private final javax.sql.DataSource _ds; //NOSONAR

    private final Connection _conn; //NOSONAR

    private final boolean _closeConnection; //NOSONAR

    private final boolean _originalAutoCommit; //NOSONAR

    private final int _originalIsolationLevel; //NOSONAR

    private Transaction.Status _status = Status.ACTIVE; //NOSONAR

    private final AtomicInteger _refCount = new AtomicInteger(); //NOSONAR

    private final Deque<IsolationLevel> _isolationLevelStack = new ConcurrentLinkedDeque<>(); //NOSONAR

    private final Deque<Boolean> _isForUpdateOnlyStack = new ConcurrentLinkedDeque<>(); //NOSONAR

    private IsolationLevel _isolationLevel; //NOSONAR

    private boolean _isForUpdateOnly; //NOSONAR

    private boolean _isMarkedByCommitPreviously = false; //NOSONAR

    SQLTransaction(final javax.sql.DataSource ds, final Connection conn, final IsolationLevel isolationLevel, final CreatedBy creator,
            final boolean closeConnection) throws SQLException {
        N.checkArgNotNull(conn);
        N.checkArgNotNull(isolationLevel);

        _id = getTransactionId(ds, creator);
        _timedId = _id + "_" + System.currentTimeMillis();
        _ds = ds;
        _conn = conn;
        _isolationLevel = isolationLevel;
        _closeConnection = closeConnection;

        _originalAutoCommit = conn.getAutoCommit();
        _originalIsolationLevel = conn.getTransactionIsolation();

        conn.setAutoCommit(false);

        if (isolationLevel != IsolationLevel.DEFAULT) {
            conn.setTransactionIsolation(isolationLevel.intValue());
        }
    }

    /**
     * Returns the unique identifier of this transaction.
     * The ID includes timestamp information to ensure uniqueness across time.
     *
     * <p><b>Usage Examples:</b></p>
     * <pre>{@code
     * SQLTransaction tran = JdbcUtil.beginTransaction(dataSource);
     * String transactionId = tran.id();
     * logger.info("Starting transaction: " + transactionId);
     * }</pre>
     *
     * @return the unique transaction identifier, never {@code null}
     */
    @Override
    public String id() {
        return _timedId;
    }

    /**
     * Returns the JDBC connection associated with this transaction.
     * This connection should not be closed manually as it will be managed by the transaction.
     *
     * <p><b>Usage Examples:</b></p>
     * <pre>{@code
     * SQLTransaction tran = JdbcUtil.beginTransaction(dataSource);
     * try {
     *     Connection conn = tran.connection();
     *     // Use the connection for custom operations
     *     try (PreparedStatement stmt = conn.prepareStatement("SELECT * FROM users WHERE id = ?")) {
     *         stmt.setLong(1, userId);
     *         ResultSet rs = stmt.executeQuery();
     *         // Process results...
     *     }
     *     tran.commit();
     * } finally {
     *     tran.rollbackIfNotCommitted();
     * }
     * }</pre>
     *
     * @return the JDBC connection used by this transaction
     */
    public Connection connection() {
        return _conn;
    }

    /**
     * Returns the isolation level of this transaction.
     * The isolation level determines how this transaction interacts with other concurrent transactions.
     *
     * <p><b>Usage Examples:</b></p>
     * <pre>{@code
     * SQLTransaction tran = JdbcUtil.beginTransaction(dataSource, IsolationLevel.SERIALIZABLE);
     * IsolationLevel level = tran.isolationLevel();
     * if (level == IsolationLevel.SERIALIZABLE) {
     *     // Handle high isolation scenario
     * }
     * }</pre>
     *
     * @return the transaction isolation level, never {@code null}
     *
     * @see IsolationLevel
     */
    @Override
    public IsolationLevel isolationLevel() {
        return _isolationLevel;
    }

    /**
     * Returns the current status of this transaction.
     * The status indicates whether the transaction is active, committed, rolled back, or marked for rollback.
     *
     * <p><b>Usage Examples:</b></p>
     * <pre>{@code
     * SQLTransaction tran = JdbcUtil.beginTransaction(dataSource);
     * Transaction.Status status = tran.status();
     * if (status == Transaction.Status.ACTIVE) {
     *     // Transaction is still active and can be committed or rolled back
     * }
     * }</pre>
     *
     * @return the current transaction status, never {@code null}
     *
     * @see Transaction.Status
     */
    @Override
    public Transaction.Status status() {
        return _status;
    }

    /**
     * Checks if this transaction is currently active.
     * A transaction is active if it has not been committed, rolled back, or marked for rollback.
     *
     * <p>This is a convenience method equivalent to checking if the status
     * equals {@link Status#ACTIVE}.</p>
     *
     * <p><b>Usage Examples:</b></p>
     * <pre>{@code
     * SQLTransaction tran = JdbcUtil.beginTransaction(dataSource);
     * if (tran.isActive()) {
     *     // Safe to perform operations within this transaction
     *     performDatabaseOperations();
     * }
     * }</pre>
     *
     * @return {@code true} if the transaction is active, {@code false} otherwise
     */
    @Override
    public boolean isActive() {
        return _status == Status.ACTIVE;
    }

    //    /**
    //     * Attaches this transaction to current thread.
    //     *
    //     */
    //    public void attach() {
    //        final String currentThreadName = Thread.currentThread().getName();
    //        final String resourceId = ttid.substring(ttid.lastIndexOf('_') + 1);
    //        final String targetTTID = currentThreadName + "_" + resourceId;
    //
    //        if (attachedThreadTransactionMap.containsKey(targetTTID)) {
    //            throw new IllegalStateException("Transaction(id=" + attachedThreadTransactionMap.get(targetTTID).id()
    //                    + ") has already been attached to current thread: " + currentThreadName);
    //        } else if (threadTransactionMap.containsKey(targetTTID)) {
    //            throw new IllegalStateException(
    //                    "Transaction(id=" + threadTransactionMap.get(targetTTID).id() + ") has already been created in current thread: " + currentThreadName);
    //        }
    //
    //        attachedThreadTransactionMap.put(targetTTID, this);
    //        threadTransactionMap.put(targetTTID, this);
    //    }
    //
    //    public void detach() {
    //        final String currentThreadName = Thread.currentThread().getName();
    //        final String resourceId = ttid.substring(ttid.lastIndexOf('_') + 1);
    //        final String targetTTID = currentThreadName + "_" + resourceId;
    //
    //        if (!attachedThreadTransactionMap.containsKey(targetTTID)) {
    //            throw new IllegalStateException(
    //                    "Transaction(id=" + attachedThreadTransactionMap.get(targetTTID).id() + ") is not attached to current thread: " + currentThreadName);
    //        }
    //
    //        threadTransactionMap.remove(targetTTID);
    //        attachedThreadTransactionMap.remove(targetTTID);
    //    }

    /**
     * Commits the current transaction, making all changes permanent.
     * After a successful commit, the transaction is no longer active and the connection
     * will be automatically reset and closed (if applicable).
     *
     * <p>If the transaction is marked for rollback only, it will be rolled back instead
     * of committed. After successful commit, the transaction status is set to {@link Status#COMMITTED}.</p>
     *
     * <p><b>Usage Examples:</b></p>
     * <pre>{@code
     * SQLTransaction tran = JdbcUtil.beginTransaction(dataSource);
     * try {
     *     // Perform database operations
     *     dao.insert(entity);
     *     dao.update(anotherEntity);
     *     tran.commit();
     * } catch (Exception e) {
     *     tran.rollbackIfNotCommitted();
     * }
     * }</pre>
     *
     * @throws UncheckedSQLException if an SQL error occurs during the commit
     * @throws IllegalArgumentException if the transaction is not in a valid state for committing
     */
    @Override
    public void commit() throws UncheckedSQLException {
        commit(Fn.emptyAction());
    }

    /**
     * Commits the current transaction and executes the specified action after the commit.
     * This is an internal method used for executing post-commit callbacks with nested transaction support.
     *
     * <p>When called on a nested transaction (reference count greater than 0), this method simply decrements
     * the reference count without actually committing. The actual commit only occurs when the
     * outermost transaction's commit is called (reference count reaches 0).</p>
     *
     * <p>If the transaction is marked for rollback only, it will be rolled back instead of committed.</p>
     *
     * @param actionAfterCommit the action to be executed after the current transaction is committed successfully, must not be {@code null}
     * @throws UncheckedSQLException if an SQL error occurs during the commit
     * @throws IllegalArgumentException if the transaction is not in a valid state for committing
     */
    void commit(final Runnable actionAfterCommit) throws UncheckedSQLException {
        final int refCount = decrementAndGetRef();
        _isMarkedByCommitPreviously = true;

        if (refCount > 0) {
            return;
        } else if (refCount < 0) {
            logger.warn("Transaction(id={}) is already: {}. This committing is ignored", _timedId, _status);
            return;
        }

        if (_status == Status.MARKED_ROLLBACK) {
            logger.warn("Transaction(id={}) will be rolled back because it's marked for roll back only", _timedId);
            executeRollback();
            return;
        }

        if (_status != Status.ACTIVE) {
            throw new IllegalArgumentException("Transaction(id=" + _timedId + ") is already: " + _status + ". It can not be committed"); //NOSONAR
        }

        logger.info("Committing transaction(id={})", _timedId);

        _status = Status.FAILED_COMMIT;

        try {
            if (_originalAutoCommit) {
                _conn.commit();
            }

            _status = Status.COMMITTED;
        } catch (final SQLException e) {
            throw new UncheckedSQLException("Failed to commit transaction(id=" + _id + ")", e);
        } finally {
            if (_status == Status.COMMITTED) {
                logger.info("Transaction(id={}) has been committed successfully", _timedId);

                resetAndCloseConnection();

                actionAfterCommit.run();
            } else {
                logger.warn("Failed to commit transaction(id={}). It will automatically be rolled back ", _timedId);
                executeRollback();
            }
        }
    }

    /**
     * Rolls back the current transaction, undoing all changes made within the transaction scope.
     * After a successful rollback, the transaction is no longer active and the connection
     * will be automatically reset and closed (if applicable).
     *
     * <p><b>Note:</b> This method is deprecated. Use {@link #rollbackIfNotCommitted()} instead
     * for better transaction management in try-finally blocks.</p>
     *
     * <p>Example of preferred usage:</p>
     * <pre>{@code
     * SQLTransaction tran = JdbcUtil.beginTransaction(dataSource);
     * try {
     *     // Perform database operations
     *     dao.insert(entity);
     *     tran.commit();
     * } finally {
     *     tran.rollbackIfNotCommitted(); // Safer than rollback()
     * }
     * }</pre>
     *
     * @throws UncheckedSQLException if an SQL error occurs during the rollback
     * @deprecated replaced by {@link #rollbackIfNotCommitted()}
     */
    @Deprecated
    @Override
    public void rollback() throws UncheckedSQLException {
        rollback(Fn.emptyAction());
    }

    /**
     * Rolls back the current transaction and executes the specified action after the rollback.
     * This is an internal method used for executing post-rollback callbacks with nested transaction support.
     *
     * <p>When called on a nested transaction (reference count greater than 0), this method marks the transaction
     * for rollback and decrements the reference count. The actual rollback occurs when the outermost
     * transaction completes (reference count reaches 0).</p>
     *
     * @param actionAfterRollback the action to be executed after the current transaction is rolled back, must not be {@code null}
     * @throws UncheckedSQLException if an SQL error occurs during the rollback
     * @throws IllegalStateException if the transaction is not in a valid state for rollback
     */
    void rollback(final Runnable actionAfterRollback) throws UncheckedSQLException {
        final int refCount = decrementAndGetRef();
        _isMarkedByCommitPreviously = true;

        if (refCount > 0) {
            _status = Status.MARKED_ROLLBACK;
            return;
        } else if (refCount < 0) {
            logger.warn("Transaction(id={}) is already: {}. This rollback is ignored", _timedId, _status);
            return;
        }

        if (!(_status == Status.ACTIVE || _status == Status.MARKED_ROLLBACK || _status == Status.FAILED_COMMIT)) {
            throw new IllegalStateException("Transaction(id=" + _timedId + ") is already: " + _status);
        }

        executeRollback(actionAfterRollback);
    }

    /**
     * Rolls back the transaction if it has not been committed successfully.
     * This method is safe to call multiple times and will only perform a rollback
     * if the transaction is still active or in a failed commit state.
     *
     * <p>This method is particularly useful in finally blocks or cleanup code
     * where you want to ensure a transaction is not left in an active state.
     * It will do nothing if the transaction has already been committed.</p>
     *
     * <p><b>Usage Examples:</b></p>
     * <pre>{@code
     * SQLTransaction tran = JdbcUtil.beginTransaction(dataSource);
     * try {
     *     // Perform database operations
     *     dao.insert(entity);
     *     dao.update(anotherEntity);
     *
     *     tran.commit();
     * } finally {
     *     tran.rollbackIfNotCommitted(); // Ensures cleanup
     * }
     * }</pre>
     *
     * @throws UncheckedSQLException if an SQL error occurs during the rollback
     */
    @Override
    public void rollbackIfNotCommitted() throws UncheckedSQLException {
        if (_isMarkedByCommitPreviously) { // Do nothing. It happened in finally block.
            _isMarkedByCommitPreviously = false;
            return;
        }

        final int refCount = decrementAndGetRef();

        if (refCount > 0) {
            _status = Status.MARKED_ROLLBACK;
            return;
        } else if (refCount < 0) {
            if (refCount == -1
                    && (_status == Status.COMMITTED || _status == Status.FAILED_COMMIT || _status == Status.ROLLED_BACK || _status == Status.FAILED_ROLLBACK)) {
                // Do nothing. It happened in finally block.
            } else {
                if (_status == Status.ACTIVE) {
                    executeRollback();
                } else {
                    logger.warn("Transaction(id={}) is already: {}. This rollback is ignored", _timedId, _status);
                }
            }

            return;
        }

        if (!(_status == Status.ACTIVE || _status == Status.MARKED_ROLLBACK || _status == Status.FAILED_COMMIT || _status == Status.FAILED_ROLLBACK)) {
            throw new IllegalArgumentException("Transaction(id=" + _timedId + ") is already: " + _status + ". It can not be rolled back");
        }

        executeRollback();
    }

    private void executeRollback() throws UncheckedSQLException {
        executeRollback(Fn.emptyAction());
    }

    /**
     * Executes the rollback operation and runs the specified action after completion.
     * This is an internal method that performs the actual database rollback operation,
     * resets the connection state, and executes the post-rollback callback.
     *
     * <p>The method sets the transaction status to {@link Status#FAILED_ROLLBACK} before attempting
     * the rollback, and updates it to {@link Status#ROLLED_BACK} upon success. The connection is
     * always reset and closed (if applicable) regardless of the rollback outcome.</p>
     *
     * @param actionAfterRollback the action to be executed after rollback, must not be {@code null}
     * @throws UncheckedSQLException if an SQL error occurs during the rollback
     */
    private void executeRollback(final Runnable actionAfterRollback) throws UncheckedSQLException {
        logger.warn("Rolling back transaction(id={})", _timedId);

        _status = Status.FAILED_ROLLBACK;

        try {
            if (_originalAutoCommit) {
                _conn.rollback();
            }

            _status = Status.ROLLED_BACK;
        } catch (final SQLException e) {
            throw new UncheckedSQLException(e);
        } finally {
            if (_status == Status.ROLLED_BACK) {
                logger.warn("Transaction(id={}) has been rolled back successfully", _timedId);
            } else {
                logger.warn("Failed to roll back transaction(id={})", _timedId);
            }

            resetAndCloseConnection();

            actionAfterRollback.run();
        }
    }

    /**
     * Resets the connection to its original state and closes it if necessary.
     * This is an internal method that restores the auto-commit mode and transaction isolation level
     * to their original values before the transaction was started.
     *
     * <p>If the {@code closeConnection} flag was set to {@code true} during transaction
     * creation, the connection will be released back to the data source pool.</p>
     *
     * <p>This method is called automatically after commit or rollback operations.</p>
     */
    private void resetAndCloseConnection() {
        try {
            _conn.setAutoCommit(_originalAutoCommit);
            _conn.setTransactionIsolation(_originalIsolationLevel);
        } catch (final SQLException e) {
            logger.warn("Failed to reset connection", e);
        } finally {
            if (_closeConnection) {
                JdbcUtil.releaseConnection(_conn, _ds);
            }
        }
    }

    /**
     * Increments the reference count and updates transaction settings for nested transactions.
     * This is an internal method used to support nested transaction operations by maintaining
     * a reference count and stacking isolation levels.
     *
     * <p>The isolation level stack allows nested transactions to have different isolation levels,
     * which are restored when the nested transaction completes.</p>
     *
     * @param isolationLevel the isolation level for the nested transaction, must not be {@code null}
     * @param forUpdateOnly whether this transaction level is for update operations only
     * @return the new reference count after incrementing
     * @throws IllegalStateException if the transaction is not active
     * @throws UncheckedSQLException if a database error occurs while setting the isolation level
     */
    synchronized int incrementAndGetRef(final IsolationLevel isolationLevel, final boolean forUpdateOnly) {
        if (_status != Status.ACTIVE) {
            throw new IllegalStateException("Transaction(id=" + _timedId + ") is already: " + _status);
        }

        _isMarkedByCommitPreviously = false;

        if (_conn != null) {
            try {
                if (isolationLevel == IsolationLevel.DEFAULT) {
                    // conn.setTransactionIsolation(originalIsolationLevel);
                } else {
                    _conn.setTransactionIsolation(isolationLevel.intValue());
                }
            } catch (final SQLException e) {
                throw new UncheckedSQLException(e);
            }
        }

        if (_refCount.get() > 0) {
            _isolationLevelStack.push(_isolationLevel);
            _isForUpdateOnlyStack.push(_isForUpdateOnly);
        }

        _isolationLevel = isolationLevel;
        _isForUpdateOnly = forUpdateOnly;

        return _refCount.incrementAndGet();
    }

    /**
     * Decrements the reference count and manages transaction cleanup for nested transactions.
     * This is an internal method used to support nested transaction operations.
     *
     * <p>When the reference count reaches zero, the transaction is removed from the thread-local map
     * and the transaction is considered complete. For counts greater than zero, the previous isolation
     * level is restored from the stack.</p>
     *
     * @return the new reference count after decrementing
     * @throws UncheckedSQLException if a database error occurs while restoring the isolation level
     */
    synchronized int decrementAndGetRef() throws UncheckedSQLException {
        final int res = _refCount.decrementAndGet();

        if (res == 0) {
            threadTransactionMap.remove(_id);

            logger.info("Finishing transaction(id={})", _timedId);

            logger.debug("Remaining active transactions: {}", threadTransactionMap.values());
        } else if (res > 0) {
            // Add safety checks to prevent NoSuchElementException
            if (!_isolationLevelStack.isEmpty()) {
                _isolationLevel = _isolationLevelStack.pop();
            }
            if (!_isForUpdateOnlyStack.isEmpty()) {
                _isForUpdateOnly = _isForUpdateOnlyStack.pop();
            }

            if (_conn != null) {
                try {
                    if (_isolationLevel == IsolationLevel.DEFAULT) {
                        _conn.setTransactionIsolation(_originalIsolationLevel);
                    } else {
                        _conn.setTransactionIsolation(_isolationLevel.intValue());
                    }
                } catch (final SQLException e) {
                    throw new UncheckedSQLException(e);
                }
            }
        }

        return res;
    }

    /**
     * Checks if this transaction is marked for update operations only.
     * This is an internal method used for transaction management to optimize read-only versus
     * read-write transaction handling.
     *
     * @return {@code true} if the transaction is for update only, {@code false} otherwise
     */
    boolean isForUpdateOnly() {
        return _isForUpdateOnly;
    }

    /**
     * Generates a unique transaction ID for the given data source and creator.
     * This is an internal method that creates a transaction identifier composed of the data source's
     * identity hash code, current thread ID, thread name, and creator ordinal value.
     *
     * @param ds the data source, must not be {@code null}
     * @param creator the transaction creator type, must not be {@code null}
     * @return a unique transaction identifier string, never {@code null}
     */
    static String getTransactionId(final javax.sql.DataSource ds, final CreatedBy creator) {
        return Strings.concat(System.identityHashCode(ds), "_", Thread.currentThread().getId(), "_", Thread.currentThread().getName(), "_", creator.ordinal());
    }

    /**
     * Retrieves the active transaction for the given data source and creator from the thread-local map.
     * This is an internal method used to check if a transaction already exists for the current thread.
     *
     * @param ds the data source, must not be {@code null}
     * @param creator the transaction creator type, must not be {@code null}
     * @return the active transaction for this thread, or {@code null} if none exists
     */
    static SQLTransaction getTransaction(final javax.sql.DataSource ds, final CreatedBy creator) {
        return threadTransactionMap.get(getTransactionId(ds, creator));
    }

    /**
     * Registers a transaction in the thread-local transaction map.
     * This is an internal method used to track active transactions for the current thread.
     *
     * @param tran the transaction to register, must not be {@code null}
     * @return the previously registered transaction for this thread and data source, or {@code null} if none existed
     */
    static SQLTransaction putTransaction(final SQLTransaction tran) {
        return threadTransactionMap.put(tran._id, tran);
    }

    /**
     * Executes the specified {@code Runnable} outside of this transaction context.
     * This method temporarily removes the transaction from the current thread,
     * executes the runnable, and then restores the transaction.
     *
     * <p>This is useful when you need to perform operations that should not be
     * part of the current transaction, such as logging or audit operations that
     * should not be rolled back with the main transaction.</p>
     *
     * <p><b>Usage Examples:</b></p>
     * <pre>{@code
     * SQLTransaction tran = JdbcUtil.beginTransaction(dataSource);
     * try {
     *     // Perform transactional operations
     *     dao.update(entity);
     *
     *     // Execute non-transactional operation
     *     tran.runNotInMe(() -> {
     *         // This code runs outside the transaction
     *         auditLogger.log("Entity updated");
     *     });
     *
     *     tran.commit();
     * } finally {
     *     tran.rollbackIfNotCommitted();
     * }
     * }</pre>
     *
     * @param <E> the exception type that may be thrown during execution
     * @param cmd the {@code Runnable} to be executed outside of this transaction, must not be {@code null}
     * @throws E if the {@code Runnable} throws an exception
     * @throws IllegalStateException if another transaction is opened during execution
     */
    public <E extends Throwable> void runNotInMe(final Throwables.Runnable<E> cmd) throws E {
        synchronized (_id) { //NOSONAR
            threadTransactionMap.remove(_id);

            try {
                cmd.run();
            } finally {
                if (threadTransactionMap.put(_id, this) != null) {
                    throw new IllegalStateException("Another transaction is opened but not closed in 'Transaction.runNotInMe'."); //NOSONAR
                }
            }
        }
    }

    /**
     * Executes the specified {@code Callable} outside of this transaction context.
     * This method temporarily removes the transaction from the current thread,
     * executes the callable, and then restores the transaction.
     *
     * <p>This is useful when you need to perform operations that should not be
     * part of the current transaction and return a result, such as querying
     * data that should not be affected by uncommitted changes.</p>
     *
     * <p><b>Usage Examples:</b></p>
     * <pre>{@code
     * SQLTransaction tran = JdbcUtil.beginTransaction(dataSource);
     * try {
     *     // Perform transactional operations
     *     dao.insert(entity);
     *
     *     // Query outside transaction to see committed state
     *     String timestamp = tran.callNotInMe(() -> {
     *         // This query runs outside the transaction
     *         return dao.queryForString("SELECT current_timestamp");
     *     });
     *
     *     tran.commit();
     * } finally {
     *     tran.rollbackIfNotCommitted();
     * }
     * }</pre>
     *
     * @param <R> the result type returned by the operation
     * @param <E> the exception type that may be thrown during execution
     * @param cmd the {@code Callable} to be executed outside of this transaction, must not be {@code null}
     * @return the result returned by the {@code Callable}
     * @throws E if the {@code Callable} throws an exception
     * @throws IllegalStateException if another transaction is opened during execution
     */
    public <R, E extends Throwable> R callNotInMe(final Throwables.Callable<R, E> cmd) throws E {
        synchronized (_id) { //NOSONAR
            threadTransactionMap.remove(_id);

            try {
                return cmd.call();
            } finally {
                if (threadTransactionMap.put(_id, this) != null) {
                    throw new IllegalStateException("Another transaction is opened but not closed in 'Transaction.callNotInMe'."); //NOSONAR
                }
            }
        }
    }

    /**
     * Closes this transaction by calling {@link #rollbackIfNotCommitted()}.
     * This method is provided to support try-with-resources statements.
     * 
     * <p><b>Usage Examples:</b></p>
     * <pre>{@code
     * try (SQLTransaction tran = JdbcUtil.beginTransaction(dataSource)) {
     *     // perform operations
     *     tran.commit();
     * } // Automatically calls close(), which calls rollbackIfNotCommitted()
     * }</pre>
     *
     * @see #rollbackIfNotCommitted()
     */
    @Override
    public void close() {
        rollbackIfNotCommitted();
    }

    /**
     * Returns the hash code value for this transaction.
     * The hash code is based on the transaction's unique timed ID.
     *
     * <p><b>Usage Examples:</b></p>
     * <pre>{@code
     * SQLTransaction tran1 = JdbcUtil.beginTransaction(dataSource);
     * SQLTransaction tran2 = JdbcUtil.beginTransaction(dataSource);
     *
     * Set<SQLTransaction> transactions = new HashSet<>();
     * transactions.add(tran1);
     * transactions.add(tran2);
     * }</pre>
     *
     * @return the hash code value for this transaction
     */
    @Override
    public int hashCode() {
        return _timedId.hashCode();
    }

    /**
     * Indicates whether some other object is "equal to" this transaction.
     * Two transactions are considered equal if they have the same timed ID.
     *
     * <p><b>Usage Examples:</b></p>
     * <pre>{@code
     * SQLTransaction tran1 = JdbcUtil.beginTransaction(dataSource);
     * SQLTransaction tran2 = tran1;
     *
     * if (tran1.equals(tran2)) {
     *     // Same transaction instance
     * }
     * }</pre>
     *
     * @param obj the reference object with which to compare
     * @return {@code true} if this transaction is equal to the obj argument; {@code false} otherwise
     */
    @Override
    public boolean equals(final Object obj) {
        return obj instanceof SQLTransaction && _timedId.equals(((SQLTransaction) obj)._timedId);
    }

    /**
     * Returns a string representation of this transaction.
     * The string includes the transaction's unique timed ID for logging and debugging purposes.
     *
     * <p><b>Usage Examples:</b></p>
     * <pre>{@code
     * SQLTransaction tran = JdbcUtil.beginTransaction(dataSource);
     * logger.debug("Transaction details: " + tran.toString());
     * // Output: SQLTransaction={id=...}
     * }</pre>
     *
     * @return a string representation of this transaction
     */
    @Override
    public String toString() {
        return "SQLTransaction={id=" + _timedId + "}";
    }

    /**
     * Enumeration representing the creator of a transaction.
     * This is used internally to track and manage transactions by their origin,
     * allowing different transaction scopes based on how they were created.
     */
    enum CreatedBy {
        /**
         * Transaction created by JdbcUtil for general database operations.
         */
        JDBC_UTIL,

        /**
         * Transaction created by SQLExecutor (deprecated, not used).
         * @deprecated not used
         */
        SQL_EXECUTOR
    }
}