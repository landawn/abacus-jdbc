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
import java.util.concurrent.atomic.AtomicLong;

import com.landawn.abacus.exception.UncheckedSQLException;
import com.landawn.abacus.logging.Logger;
import com.landawn.abacus.logging.LoggerFactory;
import com.landawn.abacus.util.Fn;
import com.landawn.abacus.util.N;
import com.landawn.abacus.util.Strings;
import com.landawn.abacus.util.Throwables;

/**
 * Default {@link Transaction} implementation backed by a JDBC {@link Connection}.
 *
 * <p>{@code SqlTransaction} owns the connection for the lifetime of the transaction, tracks
 * status transitions, and restores connection state when the transaction completes. Do not
 * close the connection manually while the transaction is active.</p>
 *
 * <p>Instances are not created directly; obtain one from
 * {@link JdbcUtil#beginTransaction(javax.sql.DataSource)} or one of its overloads. Each instance
 * is bound to the thread and data source on which it was started, so transaction-aware operations
 * (such as DAO calls and {@code JdbcUtil} queries against the same data source on that thread)
 * automatically enlist in it.</p>
 *
 * <p><b>&#9888; Warning:</b> A transaction and its connection must be used only on the thread that
 * started it. Transaction context is not propagated to executor tasks or other threads. This is
 * enforced: {@link #connection()}, {@link #commit()}, {@link #rollback()},
 * {@link #rollbackIfNotCommitted()}, {@link #close()}, {@link #runOutsideTransaction(Throwables.Runnable)},
 * and {@link #callOutsideTransaction(Throwables.Callable)} all throw {@link IllegalStateException}
 * when invoked from any other thread.</p>
 *
 * <p><b>Nested scopes:</b> beginning a transaction again on the same thread and data source does
 * not start a brand-new transaction; instead it re-enters this one and increments an internal
 * reference count. Each scope must be completed exactly once: call {@link #commit()} and pair it with
 * {@link #rollbackIfNotCommitted()} in a {@code finally} block (calling only one of them may leave
 * the scope open if the other path is skipped by an exception). Additional cleanup calls can consume
 * another nested scope and must not be used as a general idempotent close operation. The
 * underlying JDBC {@code COMMIT}/{@code ROLLBACK} is issued only when the outermost scope completes
 * (the reference count reaches zero). If any inner scope rolls back, the transaction is marked
 * rollback-only and the outermost commit is converted into a rollback. A nested scope may request a
 * different isolation level; the previous level is pushed onto a stack and restored when that scope exits.
 * {@link IsolationLevel#DEFAULT} inherits the currently effective level in a nested scope.</p>
 *
 * <p>This class implements {@link AutoCloseable}; {@link #close()} simply delegates to
 * {@link #rollbackIfNotCommitted()}, making it safe to use in a try-with-resources block.</p>
 *
 * <p><b>Usage Examples:</b></p>
 * <pre>{@code
 * SqlTransaction tran = JdbcUtil.beginTransaction(dataSource);
 * try {
 *     dao.save(entity);
 *     dao.update(anotherEntity);
 *     tran.commit();
 * } finally {
 *     tran.rollbackIfNotCommitted(); // no-op after a successful commit
 * }
 *
 * // Or, using try-with-resources:
 * try (SqlTransaction tran2 = JdbcUtil.beginTransaction(dataSource, IsolationLevel.SERIALIZABLE)) {
 *     dao.save(entity);
 *     tran2.commit();
 * } // close() rolls back automatically if commit() was not reached
 * }</pre>
 *
 * @see Transaction
 * @see JdbcUtil#beginTransaction(javax.sql.DataSource)
 * @see JdbcUtil#beginTransaction(javax.sql.DataSource, IsolationLevel)
 */
@SuppressWarnings("resource")
public final class SqlTransaction implements Transaction, AutoCloseable {

    /**
     * Logger for transaction lifecycle events and diagnostic messages.
     */
    private static final Logger logger = LoggerFactory.getLogger(SqlTransaction.class);

    /**
     * Registry of active transactions keyed by data source, owner thread, and creator.
     * Used to detect and join a nested transaction started on the same thread and data source.
     */
    private static final Map<TransactionKey, SqlTransaction> threadTransactionMap = new ConcurrentHashMap<>();

    /**
     * Collision-safe registry key. The diagnostic transaction id contains identity hash codes,
     * but registry correctness must not depend on those non-unique integer values.
     */
    private static final class TransactionKey {
        /**
         * The data source the transaction's connection came from; may be {@code null} for a
         * connection-owned transaction.
         */
        private final javax.sql.DataSource dataSource;
        /**
         * The thread that owns the transaction.
         */
        private final Thread thread;
        /**
         * The originator type of the transaction.
         */
        private final CreatedBy creator;

        TransactionKey(final javax.sql.DataSource dataSource, final Thread thread, final CreatedBy creator) {
            this.dataSource = dataSource;
            this.thread = thread;
            this.creator = creator;
        }

        @Override
        public int hashCode() {
            int result = System.identityHashCode(dataSource);
            result = 31 * result + System.identityHashCode(thread);
            return 31 * result + creator.hashCode();
        }

        @Override
        public boolean equals(final Object obj) {
            if (this == obj) {
                return true;
            }

            return obj instanceof TransactionKey other && dataSource == other.dataSource && thread == other.thread && creator == other.creator;
        }
    }

    // Millisecond timestamps alone are not unique: a fast transaction loop can create multiple
    // transactions on the same thread and data source during one clock tick. Keep the timestamp
    // for diagnostics and append this process-local sequence to make id()/equals()/hashCode()
    // collision-free within the JVM.
    /**
     * Process-local sequence number appended to the timestamp when building {@link #_timedId},
     * making the transaction id unique within the JVM.
     */
    private static final AtomicLong transactionIdSequence = new AtomicLong();

    /**
     * Diagnostic base identifier of this transaction, as generated by {@code getTransactionId}
     * from the data source identity hash code, the creating thread's id, and the creator ordinal.
     */
    private final String _id; //NOSONAR

    /**
     * Collision-safe registry key (data source, owner thread, creator) under which this
     * transaction is stored in {@code threadTransactionMap}.
     */
    private final TransactionKey _key; //NOSONAR

    // Dedicated per-instance monitor for runOutsideTransaction/callOutsideTransaction. Replaces
    // synchronized(_id): locking on a String is an anti-pattern (lock identity depends on String
    // interning). _id is a freshly built String per instance, so the intended semantics are
    // "lock this transaction instance" - this object preserves exactly that, safely.
    /**
     * Per-instance monitor guarding {@code runOutsideTransaction}/{@code callOutsideTransaction}.
     */
    private final Object _outsideTxLock = new Object(); //NOSONAR

    /**
     * Unique transaction identifier: {@link #_id} suffixed with a millisecond timestamp and a
     * process-local sequence number. Returned by {@link #id()} and backs {@code equals},
     * {@code hashCode}, and {@code toString}.
     */
    private final String _timedId; //NOSONAR

    /**
     * The data source the backing connection came from; used to release the connection when the
     * transaction completes. May be {@code null} when {@code closeConnection} is {@code false}.
     */
    private final javax.sql.DataSource _ds; //NOSONAR

    /**
     * The JDBC connection backing this transaction; never {@code null}.
     */
    private final Connection _conn; //NOSONAR

    /**
     * Whether the backing connection is released back to {@link #_ds} when the transaction completes.
     */
    private final boolean _closeConnection; //NOSONAR

    /**
     * The connection's auto-commit mode captured at construction, restored when the transaction completes.
     */
    private final boolean _originalAutoCommit; //NOSONAR

    /**
     * The connection's transaction isolation level captured at construction, restored when the
     * transaction completes.
     */
    private final int _originalIsolationLevel; //NOSONAR

    /**
     * The current status of this transaction, initially {@link Status#ACTIVE}; updated by
     * commit/rollback and returned by {@link #status()}.
     */
    private volatile Transaction.Status _status = Status.ACTIVE; //NOSONAR

    /**
     * Reference count of nested transaction scopes joined on this transaction; the actual
     * commit/rollback is performed only when the count reaches zero.
     */
    private final AtomicInteger _refCount = new AtomicInteger(); //NOSONAR

    /**
     * Stack of previous isolation levels saved when a nested scope joins with a different level;
     * popped to restore the previous level when the nested scope exits.
     */
    private final Deque<IsolationLevel> _isolationLevelStack = new ConcurrentLinkedDeque<>(); //NOSONAR

    /**
     * Stack of previous for-update-only flags saved when a nested scope joins; popped to restore
     * the previous flag when the nested scope exits.
     */
    private final Deque<Boolean> _isForUpdateOnlyStack = new ConcurrentLinkedDeque<>(); //NOSONAR

    /**
     * The current isolation level of this transaction; may change as nested scopes join and exit.
     */
    private volatile IsolationLevel _isolationLevel; //NOSONAR

    /**
     * Whether this transaction is currently marked for update operations only; may change as
     * nested scopes join and exit.
     */
    private volatile boolean _isForUpdateOnly; //NOSONAR

    // One-shot "skip the next cleanup" latch. Set by commit()/rollback() once they have settled
    // the transaction; consumed (and reset) by the first rollbackIfNotCommitted()/close() so that
    // call becomes a no-op after an explicit commit/rollback rather than decrementing the ref count.
    /**
     * One-shot latch marking that {@code commit()}/{@code rollback()} has already settled this
     * transaction, so the next {@code rollbackIfNotCommitted()}/{@code close()} becomes a no-op.
     */
    private volatile boolean _isMarkedByCommitOrRollbackPreviously = false; //NOSONAR

    /**
     * Constructs a new {@code SqlTransaction} backed by the given JDBC {@link Connection}.
     *
     * <p>The connection's original auto-commit mode and transaction isolation level are captured
     * so they can be restored when the transaction completes. Auto-commit is disabled and, if the
     * given {@code isolationLevel} is not {@link IsolationLevel#DEFAULT}, the connection's
     * transaction isolation is updated accordingly.</p>
     *
     * @param ds the data source the connection came from; used to release the connection on completion when {@code closeConnection} is {@code true}. May be {@code null} if {@code closeConnection} is {@code false}.
     * @param conn the JDBC connection that backs this transaction, must not be {@code null}
     * @param isolationLevel the isolation level for this transaction, must not be {@code null}
     * @param creator the originator type (see {@link CreatedBy}) used to identify the registry slot and diagnostic ID; must not be {@code null}
     * @param closeConnection if {@code true}, the connection will be released back to {@code ds} when the transaction completes
     * @throws IllegalArgumentException if {@code ds} is {@code null} while {@code closeConnection} is
     *         {@code true}, if {@code conn}, {@code isolationLevel}, or {@code creator} is {@code null},
     *         or if {@code isolationLevel} is {@link IsolationLevel#NONE}
     * @throws SQLException if reading or modifying the connection's auto-commit / isolation level fails;
     *         the original auto-commit and isolation level are restored on a best-effort basis first and
     *         any restore failure is attached as a suppressed exception
     */
    @SuppressWarnings("deprecation")
    SqlTransaction(final javax.sql.DataSource ds, final Connection conn, final IsolationLevel isolationLevel, final CreatedBy creator,
            final boolean closeConnection) throws IllegalArgumentException, SQLException {
        N.checkArgument(ds != null || !closeConnection, "'ds' must not be null when 'closeConnection' is true");
        N.checkArgNotNull(conn, cs.conn);
        N.checkArgNotNull(isolationLevel, cs.isolationLevel);
        N.checkArgument(isolationLevel != IsolationLevel.NONE,
                "'isolationLevel' must not be NONE because Connection.TRANSACTION_NONE is not a usable transaction isolation level");
        N.checkArgNotNull(creator, cs.creator);

        _key = new TransactionKey(ds, Thread.currentThread(), creator);
        _id = getTransactionId(ds, creator);
        _timedId = _id + "_" + System.currentTimeMillis() + "_" + transactionIdSequence.incrementAndGet();
        _ds = ds;
        _conn = conn;
        _isolationLevel = isolationLevel;
        _closeConnection = closeConnection;

        _originalAutoCommit = conn.getAutoCommit();
        _originalIsolationLevel = conn.getTransactionIsolation();

        // Atomically apply both mutations: if the isolation change throws AFTER setAutoCommit(false) succeeded,
        // best-effort restore the original autoCommit before propagating the exception so the partially-mutated
        // connection isn't observed (e.g., released back to the pool by an outer try/finally).
        try {
            conn.setAutoCommit(false);

            if (isolationLevel != IsolationLevel.DEFAULT) {
                conn.setTransactionIsolation(isolationLevel.intValue());
            }
        } catch (final SQLException | RuntimeException | Error e) {
            try {
                conn.setAutoCommit(_originalAutoCommit);
            } catch (final Throwable restoreException) { //NOSONAR - cleanup must not mask the primary failure
                if (restoreException != e) {
                    e.addSuppressed(restoreException);
                }
            }
            try {
                conn.setTransactionIsolation(_originalIsolationLevel);
            } catch (final Throwable restoreException) { //NOSONAR - cleanup must not mask the primary failure
                if (restoreException != e) {
                    e.addSuppressed(restoreException);
                }
            }

            throw e;
        }

        logger.info("Started transaction(id={}, isolationLevel={}, closeConnection={})", _timedId, _isolationLevel, _closeConnection);

        if (logger.isDebugEnabled()) {
            logger.debug("Original connection state for transaction(id={}): autoCommit={}, isolationLevel={}", _timedId, _originalAutoCommit,
                    _originalIsolationLevel);
        }
    }

    /**
     * Returns the unique identifier of this transaction.
     * The ID combines a timestamp with a process-local sequence number to guarantee uniqueness
     * (a millisecond timestamp alone is not unique across rapid successive transactions).
     *
     * <p>This same value backs {@link #equals(Object)}, {@link #hashCode()}, and
     * {@link #toString()}, and appears in this class's log messages, so it can be used to correlate
     * a transaction across logs.</p>
     *
     * <p><b>Usage Examples:</b></p>
     * <pre>{@code
     * try (SqlTransaction tran = JdbcUtil.beginTransaction(dataSource)) {
     *     String transactionId = tran.id();
     *     logger.info("Starting transaction: {}", transactionId);
     * }
     * }</pre>
     *
     * @return the unique transaction identifier, never {@code null}
     */
    @Override
    public String id() {
        return _timedId;
    }

    /**
     * Verifies that the current thread is the thread that created (owns) this transaction.
     *
     * @throws IllegalStateException if called from a thread other than the transaction's owner thread
     */
    private void assertOwnerThread() throws IllegalStateException {
        if (_key.thread != Thread.currentThread()) {
            throw new IllegalStateException("Transaction(id=" + _timedId + ") is bound to thread '" + _key.thread.getName()
                    + "' and cannot be used from thread '" + Thread.currentThread().getName() + "'");
        }
    }

    /**
     * Returns the JDBC connection associated with this transaction.
     * This connection should not be closed manually as it will be managed by the transaction.
     *
     * <p><b>Usage Examples:</b></p>
     * <pre>{@code
     * SqlTransaction tran = JdbcUtil.beginTransaction(dataSource);
     * try {
     *     Connection conn = tran.connection();
     *     // Use the connection for custom operations
     *     try (PreparedStatement stmt = conn.prepareStatement("SELECT * FROM users WHERE id = ?")) {
     *         stmt.setLong(1, userId);
     *         try (ResultSet rs = stmt.executeQuery()) {
     *             // Process results...
     *         }
     *     }
     *     tran.commit();
     * } finally {
     *     tran.rollbackIfNotCommitted();
     * }
     * }</pre>
     *
     * @return the JDBC connection used by this transaction
     * @throws IllegalStateException if called from a thread other than the transaction's owner thread
     */
    public Connection connection() throws IllegalStateException {
        assertOwnerThread();

        return _conn;
    }

    /**
     * Returns the isolation level of this transaction.
     * The isolation level determines how this transaction interacts with other concurrent transactions.
     *
     * <p>While nested scopes are active, this reflects the isolation level of the current
     * (innermost) scope, which may differ from the level supplied to the outermost scope; the
     * previous level is restored as each nested scope exits.</p>
     *
     * <p><b>Usage Examples:</b></p>
     * <pre>{@code
     * try (SqlTransaction tran = JdbcUtil.beginTransaction(dataSource, IsolationLevel.SERIALIZABLE)) {
     *     IsolationLevel level = tran.isolationLevel();
     *     if (level == IsolationLevel.SERIALIZABLE) {
     *         // Handle high isolation scenario
     *     }
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
     * try (SqlTransaction tran = JdbcUtil.beginTransaction(dataSource)) {
     *     Transaction.Status status = tran.status();
     *     if (status == Transaction.Status.ACTIVE) {
     *         // Transaction is still active and can be committed or rolled back
     *     }
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
     * A transaction is active only when its status is {@link Status#ACTIVE} (i.e. it has not been
     * committed, rolled back, marked for rollback, or transitioned to {@code FAILED_COMMIT}/{@code FAILED_ROLLBACK}).
     *
     * <p>This is a convenience method equivalent to checking if the status
     * equals {@link Status#ACTIVE}.</p>
     *
     * <p><b>Usage Examples:</b></p>
     * <pre>{@code
     * try (SqlTransaction tran = JdbcUtil.beginTransaction(dataSource)) {
     *     if (tran.isActive()) {
     *         // Safe to perform operations within this transaction
     *         performDatabaseOperations();
     *     }
     * }
     * }</pre>
     *
     * @return {@code true} if the transaction is active, {@code false} otherwise
     */
    @Override
    public boolean isActive() {
        return _status == Status.ACTIVE;
    }

    /**
     * Commits this transaction scope.
     *
     * <p>Behaviour with nested scopes: each call to this method decrements the internal scope
     * reference count. The actual JDBC {@code COMMIT} is issued only when the outermost scope
     * commits (reference count reaches zero). Nested invocations simply return after
     * decrementing the counter.</p>
     *
     * <p>If the transaction has been marked for rollback only (status {@link Status#MARKED_ROLLBACK}
     * because an inner scope rolled back), the outermost commit is converted into a rollback
     * rather than a commit; this method returns normally in that case (or throws
     * {@link UncheckedSQLException} if that rollback itself fails).</p>
     *
     * <p>When the outermost scope actually commits, the status transitions to
     * {@link Status#COMMITTED} on success. On failure the status first becomes
     * {@link Status#FAILED_COMMIT} and an automatic rollback is attempted, leaving the status
     * {@link Status#ROLLED_BACK} (or {@link Status#FAILED_ROLLBACK} if that rollback also fails).
     * After the commit attempt (success or failure) the connection's original auto-commit and
     * isolation level are restored, and the connection is released back to its data source if this
     * transaction was created with connection ownership. The one exception is a failed automatic
     * rollback: restoring the connection state could then commit the pending work, so it is skipped
     * and an owned connection is only released.</p>
     *
     * <p><b>Usage Examples:</b></p>
     * <pre>{@code
     * SqlTransaction tran = JdbcUtil.beginTransaction(dataSource);
     * try {
     *     // Perform database operations
     *     dao.save(entity);
     *     dao.update(anotherEntity);
     *     tran.commit();
     * } finally {
     *     tran.rollbackIfNotCommitted();
     * }
     * }</pre>
     *
     * @throws IllegalStateException if called from a thread other than the transaction's owner
     *         thread, or if the outermost commit is attempted while the transaction is not in
     *         {@link Status#ACTIVE} or {@link Status#MARKED_ROLLBACK}. If this transaction
     *         scope has already completed (reference count already below zero), the call is
     *         logged and ignored rather than throwing.
     * @throws UncheckedSQLException if an SQL error occurs while restoring a nested scope's isolation level,
     *         committing, or rolling back a rollback-only transaction. A failed database commit triggers
     *         an automatic rollback; any rollback failure is suppressed on the commit exception.
     */
    @Override
    public void commit() throws IllegalStateException, UncheckedSQLException {
        commit(Fn.emptyAction());
    }

    /**
     * Commits the current transaction and executes the specified action after the commit.
     * This is an internal method used for executing post-commit callbacks with nested transaction support.
     *
     * <p>When called on a nested transaction (reference count still greater than 0 after decrementing), this method simply decrements
     * the reference count without actually committing. The actual commit only occurs when the
     * outermost transaction's commit is called (reference count reaches 0).</p>
     *
     * <p>If the transaction is marked for rollback only (status {@link Status#MARKED_ROLLBACK}), it
     * will be rolled back instead of committed, and {@code actionAfterCommit} is not run.</p>
     *
     * <p>After a successful commit, the action runs after connection cleanup has been attempted,
     * even if cleanup fails. An action failure is suppressed on an earlier cleanup failure.</p>
     *
     * @param actionAfterCommit the action to be executed after the current transaction is committed successfully in this
     *        (outermost) scope; for a nested scope the commit is deferred to the outermost scope and this action is
     *        <i>not</i> executed (the outermost scope runs its own action). Must not be {@code null}
     * @throws IllegalStateException if called from a thread other than the transaction's owner
     *         thread, or if the outermost commit is attempted while the transaction is neither
     *         {@link Status#ACTIVE} nor {@link Status#MARKED_ROLLBACK}. If this transaction
     *         scope has already completed (reference count already below zero), the call is
     *         logged and ignored rather than throwing.
     * @throws IllegalArgumentException if {@code actionAfterCommit} is {@code null}
     * @throws UncheckedSQLException if an SQL error occurs while restoring a nested scope's isolation level,
     *         committing, or rolling back a rollback-only transaction. A failed database commit triggers
     *         an automatic rollback; any rollback failure is suppressed on the commit exception.
     */
    void commit(final Runnable actionAfterCommit) throws IllegalStateException, IllegalArgumentException, UncheckedSQLException {
        assertOwnerThread();
        N.checkArgNotNull(actionAfterCommit, cs.actionAfterCommit);

        // Set the latch only after the scope exit succeeds: decrementAndGetRef() can throw on a nested
        // exit (isolation restore failure) and restores _refCount for a retry — a pre-set latch would
        // turn the paired rollbackIfNotCommitted() into a no-op and leak the scope.
        final int refCount = decrementAndGetRef();
        _isMarkedByCommitOrRollbackPreviously = true;

        if (refCount > 0) {
            logger.debug("Deferred commit for nested transaction(id={}); remaining scopes={}", _timedId, refCount);
            return;
        } else if (refCount < 0) {
            logger.warn("Transaction(id={}) is already: {}. Commit operation ignored", _timedId, _status);
            return;
        }

        if (_status == Status.MARKED_ROLLBACK) {
            logger.warn("Transaction(id={}) will be rolled back because it is marked for rollback only", _timedId);
            executeRollback();
            return;
        }

        if (_status != Status.ACTIVE) {
            throw new IllegalStateException("Transaction(id=" + _timedId + ") is already: " + _status + ". It cannot be committed"); //NOSONAR
        }

        logger.info("Committing transaction(id={})", _timedId);

        _status = Status.FAILED_COMMIT;
        SQLException commitException = null;
        Throwable commitFailure = null;

        try {
            _conn.commit();

            _status = Status.COMMITTED;
        } catch (final SQLException e) {
            commitException = e;
            final UncheckedSQLException failure = new UncheckedSQLException("Failed to commit transaction(id=" + _timedId + ")", e);
            commitFailure = failure;
            throw failure;
        } catch (final RuntimeException | Error e) {
            commitFailure = e;
            throw e;
        } finally {
            if (_status == Status.COMMITTED) {
                logger.info("Transaction(id={}) has been committed successfully", _timedId);

                complete(actionAfterCommit, null);
            } else {
                if (commitException == null) {
                    logger.warn("Commit did not complete transaction(id={}). Automatically rolling back", _timedId);
                } else {
                    logger.warn(commitException, "Failed to commit transaction(id={}). Automatically rolling back", _timedId);
                }

                try {
                    executeRollback();
                } catch (final RuntimeException | Error rollbackEx) {
                    if (commitFailure != null && commitFailure != rollbackEx) {
                        commitFailure.addSuppressed(rollbackEx);
                    }

                    logger.warn(rollbackEx,
                            "Failed to roll back transaction(id={}) after failed commit. Rollback error suppressed to preserve original commit exception.",
                            _timedId);
                }
            }
        }
    }

    /**
     * Rolls back this transaction scope.
     *
     * <p>Behaviour with nested scopes: each call decrements the internal scope reference count.
     * For non-outermost scopes the transaction is marked as {@link Status#MARKED_ROLLBACK} and
     * the actual JDBC {@code ROLLBACK} is deferred until the outermost scope completes; the
     * outermost scope then performs the rollback regardless of whether it was asked to commit
     * or rollback.</p>
     *
     * <p>After a successful rollback the status transitions to {@link Status#ROLLED_BACK}, the
     * connection's original auto-commit and isolation level are restored, and the connection is
     * released back to its data source if this transaction was created with connection ownership.
     * If the underlying {@code Connection.rollback()} fails, the status becomes
     * {@link Status#FAILED_ROLLBACK} and an {@link UncheckedSQLException} is thrown.</p>
     *
     * <p><b>&#9888; Warning:</b> Prefer one {@link #rollbackIfNotCommitted()} call in the matching
     * {@code finally} block. It safely consumes the one-shot marker left by this scope's explicit
     * commit or rollback, but extra cleanup calls are not generally idempotent for nested scopes.</p>
     *
     * <p>Example of preferred usage:</p>
     * <pre>{@code
     * SqlTransaction tran = JdbcUtil.beginTransaction(dataSource);
     * try {
     *     // Perform database operations
     *     dao.save(entity);
     *     tran.commit();
     * } finally {
     *     tran.rollbackIfNotCommitted();   // Safer than rollback()
     * }
     * }</pre>
     *
     * @throws IllegalStateException if called from a thread other than the transaction's owner
     *         thread, or if the outermost rollback is attempted while the transaction status is
     *         not {@link Status#ACTIVE}, {@link Status#MARKED_ROLLBACK}, or
     *         {@link Status#FAILED_COMMIT}. If this transaction scope has already completed
     *         (reference count already below zero), the call is logged and ignored rather
     *         than throwing.
     * @throws UncheckedSQLException if an SQL error occurs during rollback or while restoring a nested scope's isolation level
     * @deprecated replaced by {@link #rollbackIfNotCommitted()}
     */
    @Deprecated
    @Override
    public void rollback() throws IllegalStateException, UncheckedSQLException {
        rollback(Fn.emptyAction());
    }

    /**
     * Rolls back the current transaction and executes the specified action after the rollback.
     * This is an internal method used for executing post-rollback callbacks with nested transaction support.
     *
     * <p>When called on a nested transaction (reference count still greater than 0 after decrementing),
     * this method marks the transaction for rollback ({@link Status#MARKED_ROLLBACK}). The actual rollback
     * occurs when the outermost transaction completes (reference count reaches 0).</p>
     *
     * <p>The outermost action runs after rollback and connection cleanup have been attempted,
     * even if either fails. Later failures are suppressed on the first failure.</p>
     *
     * @param actionAfterRollback the action to be executed after the rollback completes in this (outermost) scope; for a nested scope the rollback is deferred to the outermost scope and this action is <i>not</i> executed (the outermost scope runs its own action). Must not be {@code null}
     * @throws IllegalStateException if called from a thread other than the transaction's owner
     *         thread, or if the outermost rollback is attempted while the transaction status is
     *         none of {@link Status#ACTIVE}, {@link Status#MARKED_ROLLBACK}, or
     *         {@link Status#FAILED_COMMIT}. If this transaction scope has already completed
     *         (reference count already below zero), the call is logged and ignored rather than throwing.
     * @throws IllegalArgumentException if {@code actionAfterRollback} is {@code null}
     * @throws UncheckedSQLException if an SQL error occurs during rollback or while restoring a nested scope's isolation level
     */
    void rollback(final Runnable actionAfterRollback) throws IllegalStateException, IllegalArgumentException, UncheckedSQLException {
        assertOwnerThread();
        N.checkArgNotNull(actionAfterRollback, cs.actionAfterRollback);

        // Latch after the scope exit succeeds — see commit(Runnable) for the failure-path reasoning.
        final int refCount = decrementAndGetRef();
        _isMarkedByCommitOrRollbackPreviously = true;

        if (refCount > 0) {
            _status = Status.MARKED_ROLLBACK;
            logger.warn("Marked nested transaction(id={}) for rollback; remaining scopes={}", _timedId, refCount);
            return;
        } else if (refCount < 0) {
            logger.warn("Transaction(id={}) is already: {}. Rollback operation ignored", _timedId, _status);
            return;
        }

        if (!(_status == Status.ACTIVE || _status == Status.MARKED_ROLLBACK || _status == Status.FAILED_COMMIT)) {
            throw new IllegalStateException("Transaction(id=" + _timedId + ") is already: " + _status);
        }

        executeRollback(actionAfterRollback);
    }

    /**
     * Rolls back the transaction if it has not been committed successfully.
     * It rolls back when the transaction is still active, marked for rollback, or in a failed-commit state.
     *
     * <p><b>&#9888; Warning:</b> Call this once from the {@code finally} block paired with the current
     * transaction scope. Repeated calls are not a general-purpose idempotent operation for nested scopes.</p>
     *
     * <p>This method is particularly useful in finally blocks or cleanup code
     * where you want to ensure a transaction is not left in an active state.
     * It will do nothing if the transaction has already been committed or rolled back.</p>
     *
     * <p>Immediately after an explicit {@link #commit()} or {@link #rollback()} on this scope, the
     * first call to this method is a deliberate no-op: it consumes a one-shot latch set by that
     * commit/rollback rather than decrementing the scope reference count a second time. This is what
     * makes the common idiom of {@code commit()} at the end of a {@code try} block followed by
     * {@code rollbackIfNotCommitted()} in the {@code finally} block safe.</p>
     *
     * <p>For a nested (non-outermost) scope this method marks the transaction
     * {@link Status#MARKED_ROLLBACK} and defers the actual rollback to the outermost scope.</p>
     *
     * <p><b>Usage Examples:</b></p>
     * <pre>{@code
     * SqlTransaction tran = JdbcUtil.beginTransaction(dataSource);
     * try {
     *     // Perform database operations
     *     dao.save(entity);
     *     dao.update(anotherEntity);
     *
     *     tran.commit();
     * } finally {
     *     tran.rollbackIfNotCommitted();   // Ensures cleanup
     * }
     * }</pre>
     *
     * @throws IllegalStateException if called from a thread other than the transaction's owner thread, or if
     *         the outermost scope reaches the rollback while the status is none of {@link Status#ACTIVE},
     *         {@link Status#MARKED_ROLLBACK}, or {@link Status#FAILED_COMMIT}. A {@link Status#COMMITTED},
     *         {@link Status#ROLLED_BACK}, or {@link Status#FAILED_ROLLBACK} transaction returns without
     *         throwing, so that second condition is a defensive guard.
     * @throws UncheckedSQLException if an SQL error occurs during rollback or while restoring a nested scope's isolation level
     */
    @Override
    public void rollbackIfNotCommitted() throws IllegalStateException, UncheckedSQLException {
        assertOwnerThread();

        if (_isMarkedByCommitOrRollbackPreviously) { // Do nothing. It happened in finally block.
            _isMarkedByCommitOrRollbackPreviously = false;
            return;
        }

        if (_status == Status.COMMITTED || _status == Status.ROLLED_BACK || _status == Status.FAILED_ROLLBACK) {
            return;
        }

        final int refCount = decrementAndGetRef();

        if (refCount > 0) {
            _status = Status.MARKED_ROLLBACK;
            logger.warn("Marked nested transaction(id={}) for rollback; remaining scopes={}", _timedId, refCount);
            return;
        }

        if (!(_status == Status.ACTIVE || _status == Status.MARKED_ROLLBACK || _status == Status.FAILED_COMMIT)) {
            throw new IllegalStateException("Transaction(id=" + _timedId + ") is already: " + _status + ". It cannot be rolled back");
        }

        executeRollback();
    }

    /**
     * Rolls back this transaction without running any post-rollback action.
     *
     * @throws UncheckedSQLException if the JDBC connection rejects the rollback
     */
    private void executeRollback() throws UncheckedSQLException {
        executeRollback(Fn.emptyAction());
    }

    /**
     * Executes the rollback operation and runs the specified action after completion.
     * This is an internal method that performs the actual database rollback operation,
     * resets the connection state, and executes the post-rollback callback.
     *
     * <p>The method sets the transaction status to {@link Status#FAILED_ROLLBACK} before attempting
     * the rollback, and updates it to {@link Status#ROLLED_BACK} upon success. Connection state is
     * restored only after a successful rollback; changing auto-commit or isolation after a failed
     * rollback could commit the pending work. An owned connection is released in either case.</p>
     *
     * <p>The action runs after the cleanup attempt, even if rollback or cleanup fails. The first
     * failure remains primary, with distinct later failures attached as suppressed exceptions.</p>
     *
     * @param actionAfterRollback the action to be executed after rollback, must not be {@code null}
     * @throws UncheckedSQLException if the JDBC connection rejects the rollback
     */
    private void executeRollback(final Runnable actionAfterRollback) throws UncheckedSQLException {
        final Status previousStatus = _status;

        logger.info("Rolling back transaction(id={}, status={})", _timedId, previousStatus);

        _status = Status.FAILED_ROLLBACK;
        SQLException rollbackException = null;
        Throwable rollbackFailure = null;

        try {
            _conn.rollback();

            _status = Status.ROLLED_BACK;
        } catch (final SQLException e) {
            rollbackException = e;
            final UncheckedSQLException sqlFailure = new UncheckedSQLException("Failed to roll back transaction(id=" + _timedId + ")", e);
            rollbackFailure = sqlFailure;
            throw sqlFailure;
        } catch (final RuntimeException | Error e) {
            rollbackFailure = e;
            throw e;
        } finally {
            if (_status == Status.ROLLED_BACK) {
                logger.info("Transaction(id={}) has been rolled back successfully", _timedId);
            } else if (rollbackException == null) {
                logger.warn("Failed to roll back transaction(id={})", _timedId);
            } else {
                logger.warn(rollbackException, "Failed to roll back transaction(id={})", _timedId);
            }

            complete(actionAfterRollback, rollbackFailure);
        }
    }

    /**
     * Attempts connection cleanup and then runs the completion action, preserving the first failure.
     *
     * @param actionAfterCompletion the non-null action to run after the cleanup attempt
     * @param primaryFailure an already propagating failure, or {@code null} if the database operation succeeded
     */
    private void complete(final Runnable actionAfterCompletion, final Throwable primaryFailure) {
        Throwable failure = primaryFailure;

        try {
            resetAndCloseConnection();
        } catch (final RuntimeException | Error e) {
            if (failure == null) {
                failure = e;
            } else if (failure != e) {
                failure.addSuppressed(e);
            }
        }

        try {
            actionAfterCompletion.run();
        } catch (final RuntimeException | Error e) {
            if (failure == null) {
                failure = e;
            } else if (failure != e) {
                failure.addSuppressed(e);
            }
        }

        if (primaryFailure == null) {
            if (failure instanceof RuntimeException runtimeFailure) {
                throw runtimeFailure;
            } else if (failure instanceof Error error) {
                throw error;
            }
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
     *
     * <p>Package-private so {@link JdbcUtil#beginTransaction} can invoke it on the failure path after a
     * partially-constructed transaction (constructor succeeded, then {@link #incrementAndGetRef} threw), so the
     * polluted connection is restored before being released back to the pool.</p>
     *
     * <p>SQL exceptions from restoring connection state are logged. Unchecked cleanup failures are
     * propagated after the remaining cleanup steps have been attempted; subsequent failures are suppressed.</p>
     *
     * <p>After a failed rollback, state restoration is skipped to avoid implicitly committing pending
     * work. An owned connection is released; a caller-owned connection remains in its current state
     * and must be recovered or discarded by its owner.</p>
     */
    void resetAndCloseConnection() {
        if (_status == Status.FAILED_ROLLBACK) {
            if (_closeConnection) {
                JdbcUtil.releaseConnection(_conn, _ds);
            }

            return;
        }

        Throwable cleanupFailure = null;

        try {
            _conn.setAutoCommit(_originalAutoCommit);
        } catch (final SQLException e) {
            logger.warn(e, "Failed to reset autoCommit for transaction(id={}) to {}", _timedId, _originalAutoCommit);
        } catch (final RuntimeException | Error e) {
            cleanupFailure = e;
        }

        try {
            _conn.setTransactionIsolation(_originalIsolationLevel);
        } catch (final SQLException e) {
            logger.warn(e, "Failed to reset transaction isolation for transaction(id={}) to {}", _timedId, _originalIsolationLevel);
        } catch (final RuntimeException | Error e) {
            if (cleanupFailure == null) {
                cleanupFailure = e;
            } else {
                if (cleanupFailure != e) {
                    cleanupFailure.addSuppressed(e);
                }
            }
        }

        if (_closeConnection) {
            logger.debug("Releasing connection for transaction(id={})", _timedId);

            try {
                JdbcUtil.releaseConnection(_conn, _ds);
            } catch (final RuntimeException | Error e) {
                if (cleanupFailure == null) {
                    cleanupFailure = e;
                } else {
                    if (cleanupFailure != e) {
                        cleanupFailure.addSuppressed(e);
                    }
                }
            }
        }

        if (cleanupFailure instanceof RuntimeException runtimeException) {
            throw runtimeException;
        } else if (cleanupFailure instanceof Error error) {
            throw error;
        }
    }

    /**
     * Increments the reference count and updates transaction settings for nested transactions.
     * This is an internal method used to support nested transaction operations by maintaining
     * a reference count and stacking isolation levels.
     *
     * <p>For the second and deeper nested scopes, the current isolation level and forUpdateOnly flag
     * are pushed onto a stack and restored when that scope exits. This allows nested transactions to
     * have different isolation levels, which are restored when the nested transaction completes.</p>
     *
     * @param isolationLevel the isolation level for the nested transaction, must not be {@code null}
     * @param forUpdateOnly whether this transaction level is for update operations only
     * @return the new reference count after incrementing
     * @throws IllegalStateException if called from a thread other than the transaction's owner thread, or if
     *         the transaction's status is not {@link Status#ACTIVE}
     * @throws IllegalArgumentException if {@code isolationLevel} is {@code null} or {@link IsolationLevel#NONE}
     * @throws UncheckedSQLException if the JDBC connection rejects the isolation level this scope requests
     *         (attempted only when the effective level differs from the one already applied); the stacks
     *         pushed for this scope are unwound and the enclosing scope's level restored beforehand
     */
    @SuppressWarnings("deprecation")
    synchronized int incrementAndGetRef(final IsolationLevel isolationLevel, final boolean forUpdateOnly)
            throws IllegalStateException, IllegalArgumentException, UncheckedSQLException {
        assertOwnerThread();

        if (_status != Status.ACTIVE) {
            throw new IllegalStateException("Transaction(id=" + _timedId + ") is already: " + _status);
        }

        N.checkArgNotNull(isolationLevel, cs.isolationLevel);
        N.checkArgument(isolationLevel != IsolationLevel.NONE,
                "'isolationLevel' must not be NONE because Connection.TRANSACTION_NONE is not a usable transaction isolation level");

        final boolean shouldPushStacks = _refCount.get() > 0;
        // DEFAULT means do not change the connection. In a nested scope that means inheriting
        // the currently effective level, not replacing the in-memory value with DEFAULT. Otherwise,
        // after a still-deeper explicit level exits, decrementAndGetRef() would interpret DEFAULT as
        // the connection's original level and restore the wrong isolation for the active outer scope.
        final IsolationLevel effectiveIsolationLevel = shouldPushStacks && isolationLevel == IsolationLevel.DEFAULT ? _isolationLevel : isolationLevel;

        // Push recovery state BEFORE mutating the connection so a setTransactionIsolation failure
        // doesn't leave the stacks/fields inconsistent with the actual connection state. Pre-fix the
        // ordering was conn-mutate → stack-push → field-update → refcount-increment, so a throw at
        // the conn-mutate step left _refCount and _isolationLevel reflecting the outer scope but
        // potentially with a half-applied connection isolation.
        if (shouldPushStacks) {
            _isolationLevelStack.push(_isolationLevel);
            _isForUpdateOnlyStack.push(_isForUpdateOnly);
        }

        // Skip the connection mutation when the effective level is unchanged: the constructor already
        // applied the requested isolation for the outermost scope (where _isolationLevel equals the
        // requested level), and a nested scope re-requesting the current level needs no JDBC call.
        if (_conn != null && effectiveIsolationLevel != IsolationLevel.DEFAULT && effectiveIsolationLevel != _isolationLevel) {
            try {
                _conn.setTransactionIsolation(effectiveIsolationLevel.intValue());
            } catch (final SQLException e) {
                final UncheckedSQLException failure = new UncheckedSQLException(e);

                // A JDBC driver is allowed to report a failure after changing connection state.
                // Best-effort restore the isolation that belongs to the still-active outer scope.
                try {
                    _conn.setTransactionIsolation(_isolationLevel == IsolationLevel.DEFAULT ? _originalIsolationLevel : _isolationLevel.intValue());
                } catch (final Throwable restoreException) { //NOSONAR - cleanup must not mask the primary SQL failure
                    if (restoreException != e) {
                        failure.addSuppressed(restoreException);
                    }
                }

                // Pop back what we just pushed so the in-memory scope state remains intact.
                if (shouldPushStacks) {
                    _isolationLevelStack.pop();
                    _isForUpdateOnlyStack.pop();
                }
                throw failure;
            } catch (final RuntimeException | Error e) {
                // JDBC implementations can throw unchecked failures after mutating their state. Restore
                // both the physical connection and the just-pushed in-memory nesting state before the
                // original failure escapes; any restoration failure is secondary.
                try {
                    _conn.setTransactionIsolation(_isolationLevel == IsolationLevel.DEFAULT ? _originalIsolationLevel : _isolationLevel.intValue());
                } catch (final Throwable restoreException) { //NOSONAR
                    if (restoreException != e) {
                        e.addSuppressed(restoreException);
                    }
                }

                if (shouldPushStacks) {
                    _isolationLevelStack.pop();
                    _isForUpdateOnlyStack.pop();
                }

                throw e;
            }
        }

        // Clear the enclosing scope's one-shot cleanup latch only once this scope has actually been
        // entered: a failed entry opens no scope, so it must not turn the enclosing scope's pending
        // no-op cleanup (after its explicit commit/rollback) into a real scope exit.
        _isMarkedByCommitOrRollbackPreviously = false;
        _isolationLevel = effectiveIsolationLevel;
        _isForUpdateOnly = forUpdateOnly;

        final int refCount = _refCount.incrementAndGet();

        logger.debug("Entered transaction scope(id={}, isolationLevel={}, forUpdateOnly={}, refCount={})", _timedId, _isolationLevel, _isForUpdateOnly,
                refCount);

        return refCount;
    }

    /**
     * Decrements the reference count and manages transaction cleanup for nested transactions.
     * This is an internal method used to support nested transaction operations.
     *
     * <p>When the reference count reaches zero, the transaction is removed from the thread-local map
     * and the transaction is considered complete. For counts greater than zero, the previous isolation
     * level and for-update-only flag are restored from their stacks.</p>
     *
     * <p>If restoring a nested scope fails, its reference count and settings are retained for a
     * retry. The connection's nested isolation level is restored on a best-effort basis, and any
     * failure of that recovery is suppressed on the original failure.</p>
     *
     * @return the new reference count after decrementing
     * @throws IllegalStateException if called from a thread other than the transaction's owner thread
     * @throws UncheckedSQLException if the JDBC connection rejects restoration of the enclosing scope's isolation level
     */
    synchronized int decrementAndGetRef() throws IllegalStateException, UncheckedSQLException {
        assertOwnerThread();

        final int res = _refCount.decrementAndGet();

        if (res == 0) {
            threadTransactionMap.computeIfPresent(_key, (k, v) -> v == this ? null : v);

            logger.info("Finishing transaction scope(id={}, status={})", _timedId, _status);

            if (logger.isDebugEnabled()) {
                logger.debug("Remaining active transactions: {}", threadTransactionMap.values());
            }
        } else if (res > 0) {
            // Add safety checks to prevent NoSuchElementException
            final boolean isolationPoppedFromStack = !_isolationLevelStack.isEmpty();
            final boolean forUpdateOnlyPoppedFromStack = !_isForUpdateOnlyStack.isEmpty();

            // Capture pre-pop field values so a failed conn.setTransactionIsolation can restore
            // BOTH the stacks AND the fields. Pre-fix the failure path only re-pushed the stack
            // but left _isolationLevel/_isForUpdateOnly at the popped (outer) value, leaving the
            // tx in an inconsistent state where stacks and fields disagree about which scope is
            // current — observable via isolationLevel() and incorrect on the next nested push.
            final IsolationLevel preIsolationLevel = _isolationLevel;
            final boolean preIsForUpdateOnly = _isForUpdateOnly;

            if (isolationPoppedFromStack) {
                _isolationLevel = _isolationLevelStack.pop();
            }
            if (forUpdateOnlyPoppedFromStack) {
                _isForUpdateOnly = _isForUpdateOnlyStack.pop();
            }

            // Restore the physical connection only when the effective isolation actually changes.
            // Reissuing the same level is not merely a redundant round-trip: some drivers reject
            // setTransactionIsolation once transaction work has started, even for the current value.
            if (_conn != null && _isolationLevel != preIsolationLevel) {
                try {
                    if (_isolationLevel == IsolationLevel.DEFAULT) {
                        _conn.setTransactionIsolation(_originalIsolationLevel);
                    } else {
                        _conn.setTransactionIsolation(_isolationLevel.intValue());
                    }
                } catch (final SQLException | RuntimeException | Error e) {
                    // Capture the level we failed to restore to before resetting the field below,
                    // otherwise the log would report the (rolled-back) inner level instead of the target.
                    final IsolationLevel failedTargetIsolationLevel = _isolationLevel;
                    // Restore the stacks, the fields AND the ref count symmetrically, so the scope
                    // depth stays consistent with the per-scope state after the failed exit.
                    if (isolationPoppedFromStack) {
                        _isolationLevelStack.push(_isolationLevel);
                    }
                    if (forUpdateOnlyPoppedFromStack) {
                        _isForUpdateOnlyStack.push(_isForUpdateOnly);
                    }
                    _isolationLevel = preIsolationLevel;
                    _isForUpdateOnly = preIsForUpdateOnly;
                    _refCount.incrementAndGet();

                    final Throwable failure = e instanceof SQLException sqlException ? new UncheckedSQLException(sqlException) : e;

                    // The driver may have changed the physical isolation before reporting failure.
                    // Keep it aligned with the nested scope that remains active after this failed exit.
                    try {
                        _conn.setTransactionIsolation(preIsolationLevel == IsolationLevel.DEFAULT ? _originalIsolationLevel : preIsolationLevel.intValue());
                    } catch (final Throwable recoveryFailure) { //NOSONAR - preserve the original scope-exit failure
                        if (recoveryFailure != failure && recoveryFailure != e) {
                            failure.addSuppressed(recoveryFailure);
                        }
                    }

                    logger.warn(e, "Failed to restore isolation level for transaction(id={}) to {}", _timedId, failedTargetIsolationLevel);

                    if (failure instanceof RuntimeException runtimeException) {
                        throw runtimeException;
                    }

                    throw (Error) failure;
                }
            }

            logger.debug("Left nested transaction scope(id={}, isolationLevel={}, forUpdateOnly={}, refCount={})", _timedId, _isolationLevel, _isForUpdateOnly,
                    res);
        }

        return res;
    }

    /**
     * Checks if this transaction is marked for update operations only.
     * A for-update-only transaction is not enlisted for read-only ({@code SELECT}) operations;
     * such queries execute outside the transaction.
     *
     * @return {@code true} if the transaction is for update only, {@code false} otherwise
     */
    boolean isForUpdateOnly() {
        return _isForUpdateOnly;
    }

    /**
     * Generates the diagnostic base ID for a transaction created by the current thread.
     * The value is composed of the data source's identity hash code, the current thread ID, and the
     * creator's ordinal value. It is intended for readable diagnostics; the active-transaction registry
     * uses a collision-safe key containing the actual object references.
     *
     * @param ds the data source; may be {@code null} (as permitted by the constructor when {@code closeConnection} is {@code false}), in which case the identity hash code is {@code 0}.
     * @param creator the transaction creator type, must not be {@code null}
     * @return the diagnostic transaction identifier string, never {@code null}
     * @throws IllegalArgumentException if {@code creator} is {@code null}
     */
    static String getTransactionId(final javax.sql.DataSource ds, final CreatedBy creator) throws IllegalArgumentException {
        N.checkArgNotNull(creator, cs.creator);

        return Strings.concat(System.identityHashCode(ds), "_", Thread.currentThread().threadId(), "_", creator.ordinal());
    }

    /**
     * Retrieves the active transaction for the given data source and creator from the thread-local map.
     * This is an internal method used to check if a transaction already exists for the current thread.
     *
     * @param ds the data source; may be {@code null} for a connection-owned transaction
     * @param creator the transaction creator type, must not be {@code null}
     * @return the active transaction for this thread, or {@code null} if none exists
     * @throws IllegalArgumentException if {@code creator} is {@code null}
     */
    static SqlTransaction getTransaction(final javax.sql.DataSource ds, final CreatedBy creator) throws IllegalArgumentException {
        N.checkArgNotNull(creator, cs.creator);

        return threadTransactionMap.get(new TransactionKey(ds, Thread.currentThread(), creator));
    }

    /**
     * Registers a transaction in the thread-local transaction map.
     * This is an internal method used to track active transactions for the current thread.
     *
     * @param tran the transaction to register, must not be {@code null}
     * @return the previously registered transaction for this thread and data source, or {@code null} if none existed
     * @throws NullPointerException if {@code tran} is {@code null}
     */
    static SqlTransaction putTransaction(final SqlTransaction tran) throws NullPointerException {
        return threadTransactionMap.put(tran._key, tran);
    }

    /**
     * Executes the specified {@code Runnable} outside of this transaction context.
     * This method temporarily removes the transaction from the current thread,
     * executes the runnable, and then restores the transaction (only if it was registered
     * on this thread and is still active or marked rollback-only when {@code cmd} completes).
     *
     * <p>This is useful when you need to perform operations that should not be
     * part of the current transaction, such as logging or audit operations that
     * should not be rolled back with the main transaction.</p>
     *
     * <p><b>Usage Examples:</b></p>
     * <pre>{@code
     * SqlTransaction tran = JdbcUtil.beginTransaction(dataSource);
     * try {
     *     // Perform transactional operations
     *     dao.update(entity);
     *
     *     // Execute non-transactional operation
     *     tran.runOutsideTransaction(() -> {
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
     * @throws IllegalStateException if called from a thread other than the transaction's owner
     *         thread, or if, after {@code cmd} completes normally, another transaction has been
     *         opened on this thread for the same data source and creator and was not closed. If
     *         {@code cmd} itself throws, the latter condition is instead attached to that
     *         exception as a suppressed exception.
     * @throws IllegalArgumentException if {@code cmd} is {@code null}
     * @throws E if the {@code Runnable} throws an exception
     */
    public <E extends Throwable> void runOutsideTransaction(final Throwables.Runnable<E> cmd) throws IllegalStateException, IllegalArgumentException, E {
        assertOwnerThread();
        N.checkArgNotNull(cmd, cs.cmd);

        synchronized (_outsideTxLock) { //NOSONAR
            final boolean wasRegistered = threadTransactionMap.remove(_key, this);

            Throwable throwable = null;

            try {
                cmd.run();
            } catch (final Throwable e) { //NOSONAR
                throwable = e;
                throw e;
            } finally {
                if (wasRegistered && (_status == Status.ACTIVE || _status == Status.MARKED_ROLLBACK) && threadTransactionMap.putIfAbsent(_key, this) != null) {
                    final IllegalStateException ex = new IllegalStateException(
                            "Another transaction is opened but not closed in 'SqlTransaction.runOutsideTransaction'."); //NOSONAR

                    if (throwable != null) {
                        throwable.addSuppressed(ex);
                    } else {
                        throw ex;
                    }
                }
            }
        }
    }

    /**
     * Executes the specified {@code Callable} outside of this transaction context.
     * This method temporarily removes the transaction from the current thread,
     * executes the callable, and then restores the transaction (only if it was registered
     * on this thread and is still active or marked rollback-only when {@code cmd} completes).
     *
     * <p>This is useful when you need to perform operations that should not be
     * part of the current transaction and return a result, such as querying
     * data that should not be affected by uncommitted changes.</p>
     *
     * <p><b>Usage Examples:</b></p>
     * <pre>{@code
     * SqlTransaction tran = JdbcUtil.beginTransaction(dataSource);
     * try {
     *     // Perform transactional operations
     *     dao.save(entity);
     *
     *     // Query outside transaction to see committed state
     *     String timestamp = tran.callOutsideTransaction(() -> {
     *         // This query runs outside the transaction
     *         return JdbcUtil.prepareQuery(dataSource, "SELECT current_timestamp")
     *                        .findFirst(String.class)
     *                        .orElseNull();
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
     * @throws IllegalStateException if called from a thread other than the transaction's owner
     *         thread, or if, after {@code cmd} completes normally, another transaction has been
     *         opened on this thread for the same data source and creator and was not closed. If
     *         {@code cmd} itself throws, the latter condition is instead attached to that
     *         exception as a suppressed exception.
     * @throws IllegalArgumentException if {@code cmd} is {@code null}
     * @throws E if the {@code Callable} throws an exception
     */
    public <R, E extends Throwable> R callOutsideTransaction(final Throwables.Callable<? extends R, E> cmd)
            throws IllegalStateException, IllegalArgumentException, E {
        assertOwnerThread();
        N.checkArgNotNull(cmd, cs.cmd);

        synchronized (_outsideTxLock) { //NOSONAR
            final boolean wasRegistered = threadTransactionMap.remove(_key, this);

            Throwable throwable = null;

            try {
                return cmd.call();
            } catch (final Throwable e) { //NOSONAR
                throwable = e;
                throw e;
            } finally {
                if (wasRegistered && (_status == Status.ACTIVE || _status == Status.MARKED_ROLLBACK) && threadTransactionMap.putIfAbsent(_key, this) != null) {
                    final IllegalStateException ex = new IllegalStateException(
                            "Another transaction is opened but not closed in 'SqlTransaction.callOutsideTransaction'."); //NOSONAR

                    if (throwable != null) {
                        throwable.addSuppressed(ex);
                    } else {
                        throw ex;
                    }
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
     * try (SqlTransaction tran = JdbcUtil.beginTransaction(dataSource)) {
     *     // perform operations
     *     tran.commit();
     * } // Automatically calls close(), which calls rollbackIfNotCommitted()
     * }</pre>
     *
     * @throws IllegalStateException if called from a thread other than the transaction's owner thread, or if
     *         the outermost scope reaches the rollback while the status is none of {@link Status#ACTIVE},
     *         {@link Status#MARKED_ROLLBACK}, or {@link Status#FAILED_COMMIT}. An already completed
     *         transaction, and the scope that has just committed or rolled back explicitly, return without
     *         throwing.
     * @throws UncheckedSQLException if an SQL error occurs during rollback or while restoring a nested scope's isolation level
     * @see #rollbackIfNotCommitted()
     */
    @Override
    public void close() throws IllegalStateException, UncheckedSQLException {
        rollbackIfNotCommitted();
    }

    /**
     * Returns the hash code value for this transaction.
     * The hash code is based on the transaction's unique timed ID.
     *
     * <p><b>Usage Examples:</b></p>
     * <pre>{@code
     * try (SqlTransaction tran1 = JdbcUtil.beginTransaction(dataSource1);
     *         SqlTransaction tran2 = JdbcUtil.beginTransaction(dataSource2)) {
     *     Set<SqlTransaction> transactions = new HashSet<>();
     *     transactions.add(tran1);
     *     transactions.add(tran2);
     * }
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
     * try (SqlTransaction tran1 = JdbcUtil.beginTransaction(dataSource)) {
     *     SqlTransaction tran2 = tran1;
     *     if (tran1.equals(tran2)) {
     *         // Same transaction instance
     *     }
     * }
     * }</pre>
     *
     * @param obj the reference object with which to compare
     * @return {@code true} if this transaction is equal to the obj argument; {@code false} otherwise
     */
    @Override
    public boolean equals(final Object obj) {
        return obj instanceof SqlTransaction && _timedId.equals(((SqlTransaction) obj)._timedId);
    }

    /**
     * Returns a string representation of this transaction.
     * The string includes the transaction's unique timed ID for logging and debugging purposes.
     *
     * <p><b>Usage Examples:</b></p>
     * <pre>{@code
     * try (SqlTransaction tran = JdbcUtil.beginTransaction(dataSource)) {
     *     logger.debug("Transaction details: {}", tran);
     *     // Output: SqlTransaction={id=...}
     * }
     * }</pre>
     *
     * @return a string representation of this transaction
     */
    @Override
    public String toString() {
        return "SqlTransaction={id=" + _timedId + "}";
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
        JDBC_UTIL
    }
}
