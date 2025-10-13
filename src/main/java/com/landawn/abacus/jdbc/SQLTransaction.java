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

// TODO: Auto-generated Javadoc
/**
 * Represents a SQL transaction that manages database transaction lifecycle and connection state.
 * This class provides transaction management capabilities including commit, rollback, and automatic
 * resource cleanup. It supports nested transactions with reference counting and isolation level management.
 * 
 * <p><b>Important:</b> DO NOT CLOSE the connection manually. It will be automatically closed 
 * after the transaction is committed or rolled back.</p>
 * 
 * <p>Example usage:</p>
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
 * @author Haiyang Li
 * @since 1.0
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
     * @return the unique transaction identifier
     */
    @Override
    public String id() {
        return _timedId;
    }

    /**
     * Returns the JDBC connection associated with this transaction.
     * This connection should not be closed manually as it will be managed by the transaction.
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
     * @return the transaction isolation level
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
     * @return the current transaction status
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
     * Commits the current transaction.
     * This method commits all changes made within the transaction to the database.
     * If the transaction is marked for rollback only, it will be rolled back instead.
     * The connection will be automatically closed after successful commit.
     * 
     * <p>Example:</p>
     * <pre>{@code
     * SQLTransaction tran = JdbcUtil.beginTransaction(dataSource);
     * try {
     *     // perform database operations
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
     *
     * @param actionAfterCommit the action to be executed after the current transaction is committed successfully
     * @throws UncheckedSQLException if an SQL error occurs during the commit
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
     * Rolls back the current transaction.
     * This method undoes all changes made within the transaction.
     * The connection will be automatically closed after the rollback.
     * 
     * <p><b>Note:</b> This method is deprecated. Use {@link #rollbackIfNotCommitted()} instead
     * for better transaction management in try-finally blocks.</p>
     * 
     * <p>Example of preferred usage:</p>
     * <pre>{@code
     * final SQLTransaction tran = JdbcUtil.beginTransaction(dataSource);
     * try {
     *     // perform database operations
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
     *
     * @param actionAfterRollback the action to be executed after the current transaction is rolled back
     * @throws UncheckedSQLException if an SQL error occurs during the rollback
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
     * This method is designed to be called in finally blocks to ensure proper transaction cleanup.
     * It will do nothing if the transaction has already been committed.
     * 
     * <p>This is the recommended way to handle transaction cleanup:</p>
     * <pre>{@code
     * final SQLTransaction tran = JdbcUtil.beginTransaction(dataSource);
     * try {
     *     // perform database operations
     *     dao.insert(entity);
     *     dao.update(anotherEntity);
     *     
     *     tran.commit();
     * } finally {
     *     tran.rollbackIfNotCommitted(); // Safe cleanup
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
     * Executes the rollback operation.
     *
     * @param actionAfterRollback the action to be executed after rollback
     * @throws UncheckedSQLException if an SQL error occurs
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
     * Increments the reference count and updates transaction settings.
     * Used for nested transaction support.
     *
     * @param isolationLevel the isolation level for the nested transaction
     * @param forUpdateOnly whether this is a read-only transaction for updates
     * @return the new reference count
     * @throws UncheckedSQLException if the transaction is not active
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
     * Decrements the reference count and manages transaction cleanup.
     * Used for nested transaction support.
     *
     * @return the new reference count
     * @throws UncheckedSQLException if an error occurs
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
     * This is used internally for transaction management.
     *
     * @return {@code true} if the transaction is for update only, {@code false} otherwise
     */
    boolean isForUpdateOnly() {
        return _isForUpdateOnly;
    }

    /**
     * Generates a unique transaction ID for the given data source and creator.
     *
     * @param ds the data source
     * @param creator the transaction creator type
     * @return a unique transaction identifier
     */
    static String getTransactionId(final javax.sql.DataSource ds, final CreatedBy creator) {
        return Strings.concat(System.identityHashCode(ds), "_", Thread.currentThread().getId(), "_", Thread.currentThread().getName(), "_", creator.ordinal());
    }

    /**
     * Retrieves the active transaction for the given data source and creator.
     *
     * @param ds the data source
     * @param creator the transaction creator type
     * @return the active transaction, or null if none exists
     */
    static SQLTransaction getTransaction(final javax.sql.DataSource ds, final CreatedBy creator) {
        return threadTransactionMap.get(getTransactionId(ds, creator));
    }

    /**
     * Registers a transaction in the thread-local transaction map.
     *
     * @param tran the transaction to register
     * @return the previously registered transaction, or null
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
     * part of the current transaction.</p>
     * 
     * <p>Example:</p>
     * <pre>{@code
     * transaction.runNotInMe(() -> {
     *     // This code runs outside the transaction
     *     auditLogger.log("Transaction started");
     * });
     * }</pre>
     *
     * @param <E> the type of exception that the {@code Runnable} might throw
     * @param cmd the {@code Runnable} to be executed outside of this transaction
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
     * part of the current transaction and return a result.</p>
     * 
     * <p>Example:</p>
     * <pre>{@code
     * String result = transaction.callNotInMe(() -> {
     *     // This query runs outside the transaction
     *     return dao.queryForString("SELECT current_timestamp");
     * });
     * }</pre>
     *
     * @param <R> the type of the result returned by the {@code Callable}
     * @param <E> the type of exception that the {@code Callable} might throw
     * @param cmd the {@code Callable} to be executed outside of this transaction
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
     * <p>Example:</p>
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
     * @param obj the reference object with which to compare
     * @return {@code true} if this transaction is equal to the obj argument; {@code false} otherwise
     */
    @Override
    public boolean equals(final Object obj) {
        return obj instanceof SQLTransaction && _timedId.equals(((SQLTransaction) obj)._timedId);
    }

    /**
     * Returns a string representation of this transaction.
     * The string includes the transaction's unique ID.
     *
     * @return a string representation of this transaction
     */
    @Override
    public String toString() {
        return "SQLTransaction={id=" + _timedId + "}";
    }

    /**
     * Enumeration representing the creator of a transaction.
     * Used internally to track and manage transactions by their origin.
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