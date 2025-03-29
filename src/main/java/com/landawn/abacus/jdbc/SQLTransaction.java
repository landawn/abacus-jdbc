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
import java.util.Map;
import java.util.Stack;
import java.util.concurrent.ConcurrentHashMap;
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
 * DO NOT CLOSE the connection manually. It will be automatically closed after the transaction is committed or rolled back.
 *
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

    private final Stack<IsolationLevel> _isolationLevelStack = new Stack<>(); //NOSONAR

    private final Stack<Boolean> _isForUpdateOnlyStack = new Stack<>(); //NOSONAR

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
     * Returns the unique identifier of the transaction.
     *
     * @return the unique identifier of the transaction.
     */
    @Override
    public String id() {
        return _timedId;
    }

    /**
     * Returns the connection associated with this transaction.
     *
     * @return the connection associated with this transaction.
     */
    public Connection connection() {
        return _conn;
    }

    /**
     * Returns the isolation level of the transaction.
     *
     * @return the isolation level of the transaction.
     */
    @Override
    public IsolationLevel isolationLevel() {
        return _isolationLevel;
    }

    /**
     * Returns the current status of the transaction.
     *
     * @return the current status of the transaction.
     */
    @Override
    public Transaction.Status status() {
        return _status;
    }

    /**
     * Checks if the transaction is active.
     *
     * @return {@code true} if the transaction is active, {@code false} otherwise.
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
     *
     * @throws UncheckedSQLException if an SQL error occurs during the commit.
     */
    @Override
    public void commit() throws UncheckedSQLException {
        commit(Fn.emptyAction());
    }

    /**
     * Commits the current transaction and executes the specified action after the commit.
     *
     * @param actionAfterCommit the action to be executed after the current transaction is committed successfully.
     * @throws UncheckedSQLException if an SQL error occurs during the commit.
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
     *
     * <pre>
     * <code>
     *   final SQLTransaction tran = JdbcContext.beginTransaction(IsolationLevel.READ_COMMITTED);
     *   try {
     *       // sqlExecutor.insert(...);
     *       // sqlExecutor.update(...);
     *       // sqlExecutor.query(...);
     *
     *       tran.commit();
     *   } finally {
     *       // The connection will be automatically closed after the transaction is committed or rolled back.
     *       tran.rollbackIfNotCommitted();
     *   }
     * </code>
     * </pre>
     *
     * @throws UncheckedSQLException the unchecked SQL exception
     * @deprecated replaced by {@code #rollbackIfNotCommitted()}
     */
    @Deprecated
    @Override
    public void rollback() throws UncheckedSQLException {
        rollback(Fn.emptyAction());
    }

    /**
     * Rolls back the current transaction and executes the specified action after the rollback.
     *
     * @param actionAfterRollback the action to be executed after the current transaction is rolled back, not successfully or not.
     * @throws UncheckedSQLException if an SQL error occurs during the rollback.
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
     *
     * @throws UncheckedSQLException if an SQL error occurs during the rollback.
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
                logger.warn("Transaction(id={}) is already: {}. This rollback is ignored", _timedId, _status);
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
     *
     * @throws UncheckedSQLException the unchecked SQL exception
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
     * Reset and close connection.
     */
    private void resetAndCloseConnection() {
        try {
            _conn.setAutoCommit(_originalAutoCommit);
            _conn.setTransactionIsolation(_originalIsolationLevel);
        } catch (final SQLException e) {
            logger.warn("Failed to reset connection", e);
        } finally {
            if (_closeConnection) {
                JdbcContext.releaseConnection(_conn, _ds);
            }
        }
    }

    /**
     * Increment and get ref.
     *
     * @param isolationLevel
     * @param forUpdateOnly
     * @return
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
     * Decrement and get ref.
     *
     * @return
     * @throws UncheckedSQLException the unchecked SQL exception
     */
    synchronized int decrementAndGetRef() throws UncheckedSQLException {
        final int res = _refCount.decrementAndGet();

        if (res == 0) {
            threadTransactionMap.remove(_id);

            logger.info("Finishing transaction(id={})", _timedId);

            logger.debug("Remaining active transactions: {}", threadTransactionMap.values());
        } else if (res > 0) {
            _isolationLevel = _isolationLevelStack.pop();
            _isForUpdateOnly = _isForUpdateOnlyStack.pop();

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
     * Checks if is for update only.
     *
     * @return {@code true}, if is for update only
     */
    boolean isForUpdateOnly() {
        return _isForUpdateOnly;
    }

    /**
     *
     * @param ds
     * @param creator
     * @return
     */
    static String getTransactionId(final javax.sql.DataSource ds, final CreatedBy creator) {
        return Strings.concat(System.identityHashCode(ds), "_", Thread.currentThread().getId(), "_", Thread.currentThread().getName(), "_", creator.ordinal());
    }

    /**
     *
     * @param ds
     * @param creator
     * @return
     */
    static SQLTransaction getTransaction(final javax.sql.DataSource ds, final CreatedBy creator) {
        return threadTransactionMap.get(getTransactionId(ds, creator));
    }

    /**
     *
     * @param tran
     * @return
     */
    static SQLTransaction putTransaction(final SQLTransaction tran) {
        return threadTransactionMap.put(tran._id, tran);
    }

    /**
     * Executes the specified {@code Runnable} outside of this transaction.
     *
     * @param <E> the type of exception that the {@code Runnable} might throw.
     * @param cmd the {@code Runnable} to be executed outside of this transaction.
     * @throws E if the {@code Runnable} throws an exception.
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
     * Executes the specified {@code Callable} outside of this transaction.
     *
     * @param <R> the type of the result returned by the {@code Callable}.
     * @param <E> the type of exception that the {@code Callable} might throw.
     * @param cmd the {@code Callable} to be executed outside of this transaction.
     * @return the result returned by the {@code Callable}.
     * @throws E if the {@code Callable} throws an exception.
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
     * {@code rollbackIfNotCommitted} is called.
     *
     * @see #rollbackIfNotCommitted()
     */
    @Override
    public void close() {
        rollbackIfNotCommitted();
    }

    /**
     *
     *
     * @return
     */
    @Override
    public int hashCode() {
        return _timedId.hashCode();
    }

    /**
     *
     * @param obj
     * @return {@code true}, if successful
     */
    @Override
    public boolean equals(final Object obj) {
        return obj instanceof SQLTransaction && _timedId.equals(((SQLTransaction) obj)._timedId);
    }

    /**
     *
     *
     * @return
     */
    @Override
    public String toString() {
        return "SQLTransaction={id=" + _timedId + "}";
    }

    /**
     * The Enum CreatedBy.
     */
    enum CreatedBy {
        /**
         * Global for all.
         */
        JDBC_UTIL,

        /**
         * SQLExecutor.
         * @deprecated not used
         */
        SQL_EXECUTOR
    }
}
