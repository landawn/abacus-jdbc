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

import java.io.Closeable;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.Map;
import java.util.Stack;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

import com.landawn.abacus.IsolationLevel;
import com.landawn.abacus.Transaction;
import com.landawn.abacus.exception.UncheckedSQLException;
import com.landawn.abacus.logging.Logger;
import com.landawn.abacus.logging.LoggerFactory;

// TODO: Auto-generated Javadoc
/**
 * DO NOT CLOSE the connection manually. It will be automatically closed after the transaction is committed or rolled back.
 *
 * @author Haiyang Li
 * @since 0.8
 */
public final class SQLTransaction implements Transaction, Closeable {

    private static final Logger logger = LoggerFactory.getLogger(SQLTransaction.class);

    private static final Map<String, SQLTransaction> threadTransactionMap = new ConcurrentHashMap<>();
    // private static final Map<String, SQLTransaction> attachedThreadTransactionMap = new ConcurrentHashMap<>();

    private final String _id;

    private final String _timedId;

    private final javax.sql.DataSource _ds;

    private final Connection _conn;

    private final boolean _closeConnection;

    private final boolean _originalAutoCommit;

    private final int _originalIsolationLevel;

    private Transaction.Status _status = Status.ACTIVE;

    private final AtomicInteger _refCount = new AtomicInteger();

    private final Stack<IsolationLevel> _isolationLevelStack = new Stack<>();

    private final Stack<Boolean> _isForUpdateOnlyStack = new Stack<>();

    private IsolationLevel _isolationLevel;

    private boolean _isForUpdateOnly;

    private boolean _isMarkedByCommitPreviously = false;

    SQLTransaction(final javax.sql.DataSource ds, final Connection conn, final IsolationLevel isolationLevel, final CreatedBy creator,
            final boolean closeConnection) throws SQLException {
        N.checkArgNotNull(conn);
        N.checkArgNotNull(isolationLevel);

        this._id = getTransactionId(ds, creator);
        this._timedId = _id + "_" + System.currentTimeMillis();
        this._ds = ds;
        this._conn = conn;
        this._isolationLevel = isolationLevel;
        this._closeConnection = closeConnection;

        this._originalAutoCommit = conn.getAutoCommit();
        this._originalIsolationLevel = conn.getTransactionIsolation();

        conn.setAutoCommit(false);

        if (isolationLevel != IsolationLevel.DEFAULT) {
            conn.setTransactionIsolation(isolationLevel.intValue());
        }
    }

    @Override
    public String id() {
        return _timedId;
    }

    public Connection connection() {
        return _conn;
    }

    @Override
    public IsolationLevel isolationLevel() {
        return _isolationLevel;
    }

    @Override
    public Transaction.Status status() {
        return _status;
    }

    /**
     * Checks if is active.
     *
     * @return true, if is active
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
     *
     * @throws UncheckedSQLException the unchecked SQL exception
     */
    @Override
    public void commit() throws UncheckedSQLException {
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
            throw new IllegalArgumentException("Transaction(id=" + _timedId + ") is already: " + _status + ". It can not be committed");
        }

        logger.info("Committing transaction(id={})", _timedId);

        _status = Status.FAILED_COMMIT;

        try {
            if (_originalAutoCommit) {
                _conn.commit();
            }

            _status = Status.COMMITTED;
        } catch (SQLException e) {
            throw new UncheckedSQLException("Failed to commit transaction(id=" + _id + ")", e);
        } finally {
            if (_status == Status.COMMITTED) {
                logger.info("Transaction(id={}) has been committed successfully", _timedId);

                resetAndCloseConnection();
            } else {
                logger.warn("Failed to commit transaction(id={}). It will automatically be rolled back ", _timedId);
                executeRollback();
            }
        }
    }

    /**
     * Transaction can be started:
     *
     * <pre>
     * <code>
     *   final SQLTransaction tran = sqlExecutor.beginTransaction(IsolationLevel.READ_COMMITTED);
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
     * @see SQLExecutor#beginTransaction(IsolationLevel)
     * @deprecated replaced by {@code #rollbackIfNotCommitted()}
     */
    @Deprecated
    @Override
    public void rollback() throws UncheckedSQLException {
        final int refCount = decrementAndGetRef();
        _isMarkedByCommitPreviously = true;

        if (refCount > 0) {
            _status = Status.MARKED_ROLLBACK;
            return;
        } else if (refCount < 0) {
            logger.warn("Transaction(id={}) is already: {}. This rollback is ignored", _timedId, _status);
            return;
        }

        if (!(_status.equals(Status.ACTIVE) || _status.equals(Status.MARKED_ROLLBACK) || _status == Status.FAILED_COMMIT)) {
            throw new IllegalStateException("Transaction(id=" + _timedId + ") is already: " + _status);
        }

        executeRollback();
    }

    /**
     * Rollback if not committed.
     *
     * @throws UncheckedSQLException the unchecked SQL exception
     */
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

    /**
     *
     * @throws UncheckedSQLException the unchecked SQL exception
     */
    private void executeRollback() throws UncheckedSQLException {
        logger.warn("Rolling back transaction(id={})", _timedId);

        _status = Status.FAILED_ROLLBACK;

        try {
            if (_originalAutoCommit) {
                _conn.rollback();
            }

            _status = Status.ROLLED_BACK;
        } catch (SQLException e) {
            throw new UncheckedSQLException(e);
        } finally {
            if (_status == Status.ROLLED_BACK) {
                logger.warn("Transaction(id={}) has been rolled back successfully", _timedId);
            } else {
                logger.warn("Failed to roll back transaction(id={})", _timedId);
            }

            resetAndCloseConnection();
        }
    }

    /**
     * Reset and close connection.
     */
    private void resetAndCloseConnection() {
        try {
            _conn.setAutoCommit(_originalAutoCommit);
            _conn.setTransactionIsolation(_originalIsolationLevel);
        } catch (SQLException e) {
            logger.warn("Failed to reset connection", e);
        } finally {
            if (_closeConnection) {
                JdbcUtil.releaseConnection(_conn, _ds);
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
        if (!_status.equals(Status.ACTIVE)) {
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
            } catch (SQLException e) {
                throw new UncheckedSQLException(e);
            }
        }

        if (_refCount.get() > 0) {
            this._isolationLevelStack.push(this._isolationLevel);
            this._isForUpdateOnlyStack.push(this._isForUpdateOnly);
        }

        this._isolationLevel = isolationLevel;
        this._isForUpdateOnly = forUpdateOnly;

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
            this._isolationLevel = _isolationLevelStack.pop();
            this._isForUpdateOnly = _isForUpdateOnlyStack.pop();

            if (_conn != null) {
                try {
                    if (_isolationLevel == IsolationLevel.DEFAULT) {
                        _conn.setTransactionIsolation(_originalIsolationLevel);
                    } else {
                        _conn.setTransactionIsolation(_isolationLevel.intValue());
                    }
                } catch (SQLException e) {
                    throw new UncheckedSQLException(e);
                }
            }
        }

        return res;
    }

    /**
     * Checks if is for update only.
     *
     * @return true, if is for update only
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
    static String getTransactionId(javax.sql.DataSource ds, final CreatedBy creator) {
        return StringUtil.concat(System.identityHashCode(ds), "_", Thread.currentThread().getId(), "_", Thread.currentThread().getName(), "_",
                creator.ordinal());
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
     * Execute the specified {@code Runnable} not this transaction.
     *
     * @param <E>
     * @param cmd
     * @throws E
     */
    public <E extends Throwable> void runNotInMe(Throwables.Runnable<E> cmd) throws E {
        threadTransactionMap.remove(_id);

        try {
            cmd.run();
        } finally {
            if (threadTransactionMap.put(_id, this) != null) {
                throw new IllegalStateException("Another transaction is opened but not closed in 'Transaction.runNotInMe'.");
            }
        }
    }

    /**
     * Execute the specified {@code Callable} not this transaction.
     *
     * @param <R>
     * @param <E>
     * @param cmd
     * @return
     * @throws E
     */
    public <R, E extends Throwable> R callNotInMe(Throwables.Callable<R, E> cmd) throws E {
        threadTransactionMap.remove(_id);

        try {
            return cmd.call();
        } finally {
            if (threadTransactionMap.put(_id, this) != null) {
                throw new IllegalStateException("Another transaction is opened but not closed in 'Transaction.callNotInMe'.");
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

    @Override
    public int hashCode() {
        return _timedId.hashCode();
    }

    /**
     *
     * @param obj
     * @return true, if successful
     */
    @Override
    public boolean equals(Object obj) {
        return obj instanceof SQLTransaction && _timedId.equals(((SQLTransaction) obj)._timedId);
    }

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
        SQL_EXECUTOR;
    }
}
