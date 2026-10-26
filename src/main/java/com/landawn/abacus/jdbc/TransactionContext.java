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
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

import com.landawn.abacus.exception.UncheckedSQLException;
import com.landawn.abacus.jdbc.SqlTransaction.CreatedBy;
import com.landawn.abacus.jdbc.SqlTransaction.Status;
import com.landawn.abacus.logging.Logger;
import com.landawn.abacus.logging.LoggerFactory;
import com.landawn.abacus.util.N;
import com.landawn.abacus.util.Strings;
import com.landawn.abacus.util.Throwables;

/**
 * The state one database transaction shares among its nested scopes: the connection, the status, the rollback-only mark,
 * the rollback actions and the stack of open scopes. Every {@link JdbcUtil#beginTransaction(javax.sql.DataSource)} call
 * on the same thread and data source returns a new {@link SqlTransaction} handle (a scope) over one context; only the
 * outermost scope of a transaction of its own commits or rolls back the connection (a transaction participating in a
 * Spring-managed transaction leaves the connection and its outcome to Spring).
 *
 * <p>Each scope records whether it has completed, so a scope's paired cleanup call ({@code rollbackIfNotCommitted()} or
 * {@code close()}) after its {@code commit()} is recognised by the scope itself. A scope that completes while scopes opened
 * inside it are still open exits them first: they were never completed, so the transaction is marked rollback-only, and a
 * {@code commit()} of the completing scope throws ({@link RollbackOnlyException} once the outermost scope has rolled back,
 * {@link IllegalStateException} for a nested one), as does a later {@code commit()} of an exited scope, of a scope that
 * ended without a commit (rolled back, or its commit failed), or of one that committed ({@code commit()} may be called only
 * once per scope). An outermost {@code commit()} of a transaction marked
 * rollback-only rolls back and throws {@link RollbackOnlyException}; until then, the transaction still accepts new scopes,
 * whose work is rolled back with it. A rollback that
 * fails (for a participating transaction: the rollback-only mark of the Spring-managed transaction, also one that does not
 * reach every transaction deciding the outcome) throws its own failure instead, and so does a connection cleanup that fails
 * after the rollback; such a {@code commit()} attaches the reason it failed, worded after the rollback's outcome.</p>
 *
 * <p>Not thread-safe: a context is used only on the thread that created it (enforced by {@link #assertOwnerThread()}).
 * {@link #status()} may be read from any thread, and so may {@link #assertDeferredQueryCanRun()} be called, which reads the
 * volatile status and the completion of the Spring transaction participated in only: a lazy stream that a query prepared in
 * the transaction returned may run on a parallel stream's worker threads. {@link #toString()} reads nothing that changes,
 * so the contexts of other threads may be logged.</p>
 */
@SuppressWarnings("resource")
final class TransactionContext {

    /**
     * Logger for transaction lifecycle events; logs under {@link SqlTransaction}'s name, as before the split.
     */
    private static final Logger logger = LoggerFactory.getLogger(SqlTransaction.class);

    /**
     * Registry of active transactions keyed by data source, owner thread, and creator.
     */
    private static final Map<TransactionKey, TransactionContext> threadTransactionMap = new ConcurrentHashMap<>();

    /**
     * Collision-safe registry key: the data source and thread are compared by identity.
     */
    private static final class TransactionKey {
        private final javax.sql.DataSource dataSource;
        private final Thread thread;
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

    /**
     * Process-local sequence number appended to the timestamp of {@link #_timedId}.
     */
    private static final AtomicLong transactionIdSequence = new AtomicLong();

    private final TransactionKey _key; //NOSONAR

    /**
     * Unique transaction identifier: the diagnostic base ID ({@link #getTransactionId(javax.sql.DataSource, CreatedBy)})
     * suffixed with a millisecond timestamp and a process-local sequence number.
     */
    private final String _timedId; //NOSONAR

    private final javax.sql.DataSource _ds; //NOSONAR

    private final Connection _conn; //NOSONAR

    private final boolean _closeConnection; //NOSONAR

    /**
     * Whether this transaction participates in the Spring-managed transaction that owns the connection: it then never
     * changes, commits or rolls back the connection, and a rollback marks the Spring transaction rollback-only instead.
     */
    private final boolean _springParticipating; //NOSONAR

    /**
     * Whether the connection was acquired through Spring's {@code DataSourceUtils} (Spring participation was enabled on the
     * thread when the transaction began): it is then released to Spring, whatever the thread's setting is when the
     * transaction completes.
     */
    private final boolean _acquiredThroughSpring; //NOSONAR

    /**
     * For a participating transaction, the resource Spring bound to the data source for the transaction it participates in
     * (its connection holder): while Spring binds another one, or none, the transaction is hidden from
     * {@link #getTransaction(javax.sql.DataSource, CreatedBy)}. Held strongly for the transaction's lifetime. WHY not weakly
     * once its scopes have ended, as was tried: the DAO caches don't wait for the holder's garbage collection (it proves no
     * settlement, see {@code SpringTransactionOwners}), so holding it weakly only added a "collected means completed" case to
     * the checks of a {@code SqlTransaction} the application keeps, and of the queries prepared in it.
     */
    private final Object _springResource; //NOSONAR

    /**
     * For a participating transaction, the Spring transaction it participates in as its transaction manager reports it to a
     * {@link SpringTransactionTracker} (see {@link #hasParticipatedTransactionCompleted()}); {@code null} only for a
     * non-participating transaction. Tracking must be registered before the Spring transaction begins.
     */
    private final SpringTransactionOwners.Owner _springOwner; //NOSONAR

    /**
     * The participating transaction registered under the same key that this one hides (see {@link #putTransaction}), registered
     * again when this one's outermost scope exits; {@code null} if none.
     */
    private TransactionContext _shadowed; //NOSONAR

    /**
     * Actions to run (newest first) if the outermost scope ends without a successful commit; {@code null} until the first
     * registration. In a participating transaction they are registered with Spring's transaction synchronization instead,
     * when it is active.
     */
    private RollbackActionLog _rollbackActions; //NOSONAR

    /**
     * Actions to run once the outermost scope has committed (or its commit failed, see {@link #addCommitAction}), in
     * registration order; {@code null} until the first registration. Discarded if the transaction rolls back otherwise. Not
     * used by a participating transaction (see
     * {@link JdbcUtil#runAfterCommit(javax.sql.DataSource, Object, Runnable)}).
     */
    private Map<Object, Runnable> _commitActions; //NOSONAR

    private final boolean _originalAutoCommit; //NOSONAR

    private final int _originalIsolationLevel; //NOSONAR

    private volatile Status _status = Status.ACTIVE; //NOSONAR

    /**
     * The open scopes, innermost first. Empty once the outermost scope has completed.
     */
    private final Deque<SqlTransaction> _openScopes = new ArrayDeque<>(); //NOSONAR

    /**
     * The isolation level the connection currently runs at for the innermost open scope ({@link IsolationLevel#DEFAULT}:
     * the connection's original level).
     */
    private IsolationLevel _isolationLevel; //NOSONAR

    /**
     * Constructs a context backed by {@code conn}, optionally participating in the Spring-managed transaction that owns it.
     * A non-participating context captures the connection's auto-commit mode and isolation level, disables auto-commit and
     * applies {@code isolationLevel} (unless it is {@link IsolationLevel#DEFAULT} or already in effect); a participating one
     * leaves the connection untouched. No scope is open yet: {@link #enterScope(IsolationLevel)} opens the first.
     *
     * @param ds the data source the connection came from; must not be {@code null} when {@code closeConnection} or
     *        {@code springParticipating} is {@code true}
     * @param conn the JDBC connection that backs this transaction, must not be {@code null}; acquired by
     *        {@link JdbcUtil#getConnection(javax.sql.DataSource)} on the current thread just before, as it is released the way
     *        that method acquires connections now
     * @param isolationLevel the isolation level of the outermost scope, must not be {@code null} or {@link IsolationLevel#NONE}
     * @param creator the originator type, must not be {@code null}
     * @param closeConnection if {@code true}, the connection is released back to {@code ds} when the transaction completes
     * @param springParticipating whether the transaction participates in the Spring-managed transaction owning {@code conn}
     * @throws IllegalArgumentException if an argument is invalid
     * @throws SQLException if reading or modifying the connection's auto-commit / isolation level fails; the settings this
     *         constructor attempted to change are restored on a best-effort basis first
     */
    @SuppressWarnings("deprecation")
    TransactionContext(final javax.sql.DataSource ds, final Connection conn, final IsolationLevel isolationLevel, final CreatedBy creator,
            final boolean closeConnection, final boolean springParticipating) throws IllegalArgumentException, SQLException {
        N.checkArgument(ds != null || !closeConnection, "'ds' must not be null when 'closeConnection' is true");
        N.checkArgument(ds != null || !springParticipating, "'ds' must not be null when 'springParticipating' is true");
        N.checkArgNotNull(conn, cs.conn);
        N.checkArgNotNull(isolationLevel, cs.isolationLevel);
        N.checkArgument(isolationLevel != IsolationLevel.NONE,
                "'isolationLevel' must not be NONE because Connection.TRANSACTION_NONE is not a usable transaction isolation level");
        N.checkArgNotNull(creator, cs.creator);

        _key = new TransactionKey(ds, Thread.currentThread(), creator);
        _timedId = getTransactionId(ds, creator) + "_" + System.currentTimeMillis() + "_" + transactionIdSequence.incrementAndGet();
        _ds = ds;
        _conn = conn;
        _closeConnection = closeConnection;
        _springParticipating = springParticipating;
        _acquiredThroughSpring = springParticipating || JdbcUtil.isSpringTransactionalUsed(ds);
        _springResource = springParticipating ? JdbcUtil.boundSpringResource(ds) : null;
        _springOwner = springParticipating ? SpringTransactionOwners.require(_springResource) : null;

        if (springParticipating) {
            JdbcUtil.participationSynchronization(); // Undo/cache actions still follow Spring's outcome, not scope completion.
            // Spring owns the connection's state: it set auto-commit and isolation for its transaction and restores them when
            // the transaction completes. Changing them here would alter Spring's transaction, so they are neither read nor set.
            _isolationLevel = IsolationLevel.DEFAULT;
            _originalAutoCommit = false;
            _originalIsolationLevel = Connection.TRANSACTION_NONE;

            logger.info("Started transaction(id={}) participating in the Spring-managed transaction", _timedId);
            return;
        }

        _isolationLevel = isolationLevel;
        _originalAutoCommit = conn.getAutoCommit();
        _originalIsolationLevel = conn.getTransactionIsolation();

        // Only settings this constructor attempted to change are restored on failure.
        boolean autoCommitAttempted = false;
        boolean isolationAttempted = false;

        try {
            if (_originalAutoCommit) {
                autoCommitAttempted = true;
                conn.setAutoCommit(false);
            }

            // Avoid a redundant driver call: some drivers reject even reapplying the same isolation level.
            if (isolationLevel != IsolationLevel.DEFAULT && isolationLevel.intValue() != _originalIsolationLevel) {
                isolationAttempted = true;
                conn.setTransactionIsolation(isolationLevel.intValue());
            }
        } catch (final SQLException | RuntimeException | Error e) {
            if (autoCommitAttempted) {
                try {
                    conn.setAutoCommit(_originalAutoCommit);
                } catch (final Throwable restoreException) { //NOSONAR - cleanup must not mask the primary failure
                    if (restoreException != e) {
                        e.addSuppressed(restoreException);
                    }
                }
            }

            if (isolationAttempted) {
                try {
                    conn.setTransactionIsolation(_originalIsolationLevel);
                } catch (final Throwable restoreException) { //NOSONAR - cleanup must not mask the primary failure
                    if (restoreException != e) {
                        e.addSuppressed(restoreException);
                    }
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

    String id() {
        return _timedId;
    }

    void assertOwnerThread() throws IllegalStateException {
        if (_key.thread != Thread.currentThread()) {
            throw new IllegalStateException("Transaction(id=" + _timedId + ") is bound to thread '" + _key.thread.getName()
                    + "' and cannot be used from thread '" + Thread.currentThread().getName() + "'");
        }
    }

    Connection connection() throws IllegalStateException {
        assertOwnerThread();

        return _conn;
    }

    /**
     * Returns the isolation level the connection runs at for the innermost open scope.
     *
     * @return the current isolation level; {@link IsolationLevel#DEFAULT} for a participating transaction
     */
    IsolationLevel isolationLevel() {
        return _isolationLevel;
    }

    Status status() {
        return _status;
    }

    boolean isSpringParticipating() {
        return _springParticipating;
    }

    /**
     * Returns the number of rollback actions this transaction has recorded itself (not those registered with Spring's
     * transaction synchronization).
     *
     * @return the number of recorded rollback actions
     */
    int rollbackActionCount() {
        return _rollbackActions == null ? 0 : _rollbackActions.size();
    }

    /**
     * Checks that a query prepared on this transaction's connection (from its data source) may still run: while the
     * transaction is open, or, for a transaction participating in a Spring-managed one (whose connection Spring keeps until
     * its own transaction completes), while that Spring transaction runs. Once completed, the connection has been released,
     * to a pool that may hand it to another thread. Only the completion is checked, not the thread:
     * {@code AbstractQuery.assertNotClosed()} checks the owner thread first ({@link #assertOwnerThread()}), while the deferred
     * execution of a query's lazy result (a {@code Stream} the owner thread created) may run on a parallel stream's worker
     * thread, while the owner thread waits for its result; refusing the worker thread failed such streams, which ran fine
     * before. Any thread passes, so such a stream must not be handed to another thread that consumes it while the owner thread
     * goes on using the connection.
     *
     * @throws IllegalStateException if a transaction of its own has completed, or, for a transaction participating in a
     *         Spring-managed transaction, that Spring transaction is known to have completed or to run its completion
     *         callbacks (see {@link #hasParticipatedTransactionCompleted()}); the scope's own completion does not stop a query
     *         from running on Spring's connection
     */
    void assertDeferredQueryCanRun() throws IllegalStateException {
        if (_springParticipating) {
            // WHY: a query prepared in a participating transaction ran after the Spring transaction had completed, on the
            // connection Spring had released by then (or, in its completion callbacks, in a transaction of the connection that
            // Spring's cleanup committed whatever its outcome). Not read from the thread's binding: any thread checks here.
            if (hasParticipatedTransactionCompleted()) {
                throw new IllegalStateException(
                        "Transaction(id=" + _timedId + "): a query prepared in it can't run any more because " + springCompletionReason());
            }

            return;
        }

        // The status, not the open scopes: it is volatile, and read on another thread here.
        if (_status != Status.ACTIVE && _status != Status.MARKED_ROLLBACK) {
            throw new IllegalStateException("Transaction(id=" + _timedId + ") has completed (status: " + _status + "): a query prepared in it can't run"
                    + " any more, as the connection it was prepared on has been released");
        }
    }

    /**
     * Returns the number of open scopes.
     *
     * @return the nesting depth; {@code 0} once the outermost scope has completed
     */
    int depth() {
        return _openScopes.size();
    }

    /**
     * Opens a scope: the first one is the outermost scope, later ones are nested in the innermost open scope. A nested scope
     * requesting an isolation level other than the effective one applies it to the connection until the scope exits;
     * {@link IsolationLevel#DEFAULT} inherits the effective level, and a participating transaction ignores the request.
     *
     * @param isolationLevel the isolation level requested for the scope, must not be {@code null} or {@link IsolationLevel#NONE}
     * @return the new scope
     * @throws IllegalStateException if called from a thread other than the owner thread, or if the transaction is neither
     *         {@link Status#ACTIVE} nor {@link Status#MARKED_ROLLBACK} (it has completed)
     * @throws IllegalArgumentException if {@code isolationLevel} is {@code null} or {@link IsolationLevel#NONE}
     * @throws UncheckedSQLException if the connection rejects the requested isolation level; the enclosing scope's level is
     *         restored on a best-effort basis and no scope is opened
     */
    @SuppressWarnings("deprecation")
    SqlTransaction enterScope(final IsolationLevel isolationLevel) throws IllegalStateException, IllegalArgumentException, UncheckedSQLException {
        assertOwnerThread();

        // A transaction marked rollback-only accepts new scopes, as a Spring transaction does: their work is rolled back with it,
        // and its outermost commit() throws RollbackOnlyException. Refused, a REQUIRED DAO method or callInTransaction run after
        // a caught failure threw IllegalStateException before it ran, while plain statements kept running in the transaction.
        if (_status != Status.ACTIVE && _status != Status.MARKED_ROLLBACK) {
            throw new IllegalStateException("Transaction(id=" + _timedId + ") is already: " + _status);
        }

        N.checkArgNotNull(isolationLevel, cs.isolationLevel);
        N.checkArgument(isolationLevel != IsolationLevel.NONE,
                "'isolationLevel' must not be NONE because Connection.TRANSACTION_NONE is not a usable transaction isolation level");

        final boolean nested = !_openScopes.isEmpty();
        // DEFAULT means "do not change the connection": in a nested scope it inherits the effective level.
        final IsolationLevel effectiveIsolationLevel = _springParticipating ? IsolationLevel.DEFAULT
                : nested && isolationLevel == IsolationLevel.DEFAULT ? _isolationLevel : isolationLevel;

        // Compare the physical levels: the constructor already applied the outermost scope's level, and some drivers reject
        // setTransactionIsolation once transaction work has started, even for the current value.
        if (effectiveIsolationLevel != IsolationLevel.DEFAULT
                && toConnectionIsolationLevel(effectiveIsolationLevel) != toConnectionIsolationLevel(_isolationLevel)) {
            try {
                _conn.setTransactionIsolation(effectiveIsolationLevel.intValue());
            } catch (final SQLException e) {
                final UncheckedSQLException failure = new UncheckedSQLException(e);
                restoreIsolationLevel(_isolationLevel, failure, e);
                throw failure;
            } catch (final RuntimeException | Error e) {
                restoreIsolationLevel(_isolationLevel, e, e);
                throw e;
            }
        }

        final SqlTransaction scope = new SqlTransaction(this, !nested, effectiveIsolationLevel);

        _openScopes.push(scope);
        _isolationLevel = effectiveIsolationLevel;

        logger.debug("Entered transaction scope(id={}, isolationLevel={}, depth={})", _timedId, _isolationLevel, _openScopes.size());

        return scope;
    }

    /**
     * Best-effort restores the connection's isolation level after a failed change, attaching a restore failure to
     * {@code failure}.
     */
    private void restoreIsolationLevel(final IsolationLevel isolationLevel, final Throwable failure, final Throwable original) {
        try {
            _conn.setTransactionIsolation(toConnectionIsolationLevel(isolationLevel));
        } catch (final Throwable restoreException) { //NOSONAR - cleanup must not mask the primary failure
            if (restoreException != failure && restoreException != original) {
                failure.addSuppressed(restoreException);
            }
        }
    }

    /**
     * Exits {@code scope}, which must be the innermost open scope. Exiting the outermost scope unregisters the transaction
     * from the thread; exiting a nested scope restores the enclosing scope's isolation level on the connection when it
     * differs.
     *
     * @param scope the innermost open scope
     * @param forceExit if {@code true}, the scope is exited even when the enclosing scope's isolation level cannot be
     *        restored, and the transaction is marked {@link Status#MARKED_ROLLBACK}: an {@link SQLException} is logged, while
     *        a {@link RuntimeException} or an {@link Error} is propagated once the exit has been recorded; if {@code false},
     *        such a failure leaves the scope open (for a retry) and propagates
     * @throws IllegalStateException if {@code scope} is not the innermost open scope
     * @throws UncheckedSQLException if {@code forceExit} is {@code false} and the connection rejects restoring the enclosing
     *         scope's isolation level
     */
    private void exitScope(final SqlTransaction scope, final boolean forceExit) throws IllegalStateException, UncheckedSQLException {
        if (_openScopes.peek() != scope) {
            throw new IllegalStateException("Transaction(id=" + _timedId + "): the scope to exit is not the innermost open scope");
        }

        _openScopes.pop();

        if (_openScopes.isEmpty()) {
            // A participating transaction this one hid (see putTransaction) is registered again, unless it has completed meanwhile.
            // Not registered itself (inside its own callOutsideTransaction), this one keeps it for that method to register.
            threadTransactionMap.computeIfPresent(_key, (k, v) -> v.unlink(this));

            logger.info("Finishing transaction scope(id={}, status={})", _timedId, _status);

            if (logger.isDebugEnabled()) {
                logger.debug("Remaining active transactions: {}", threadTransactionMap.values());
            }

            return;
        }

        final SqlTransaction enclosing = _openScopes.peek();
        final IsolationLevel preIsolationLevel = _isolationLevel;

        _isolationLevel = enclosing.isolationLevel();

        Throwable forcedExitFailure = null;

        // Restore only when the effective level actually changes: some drivers reject even reapplying the current value.
        if (toConnectionIsolationLevel(_isolationLevel) != toConnectionIsolationLevel(preIsolationLevel)) {
            try {
                _conn.setTransactionIsolation(toConnectionIsolationLevel(_isolationLevel));
            } catch (final SQLException | RuntimeException | Error e) {
                if (forceExit) {
                    // The cleanup path: exit anyway (a retry would keep failing, e.g. on PostgreSQL). The enclosing scope can no
                    // longer run at its own isolation level, so the transaction must not commit.
                    if (_status == Status.ACTIVE) {
                        _status = Status.MARKED_ROLLBACK;
                    }

                    logger.warn(e, "Failed to restore isolation level for transaction(id={}) to {}; exited the scope and marked the transaction for rollback",
                            _timedId, _isolationLevel);

                    if (!(e instanceof SQLException)) {
                        forcedExitFailure = e;
                    }
                } else {
                    // Undo the exit so it can be retried by the scope's cleanup call.
                    final IsolationLevel failedTargetIsolationLevel = _isolationLevel;
                    _openScopes.push(scope);
                    _isolationLevel = preIsolationLevel;

                    final Throwable failure = e instanceof SQLException sqlException ? new UncheckedSQLException(sqlException) : e;

                    // The driver may have changed the physical isolation before reporting the failure.
                    restoreIsolationLevel(preIsolationLevel, failure, e);

                    logger.warn(e, "Failed to restore isolation level for transaction(id={}) to {}", _timedId, failedTargetIsolationLevel);

                    throwUnchecked(failure);
                }
            }
        }

        logger.debug("Left nested transaction scope(id={}, isolationLevel={}, depth={})", _timedId, _isolationLevel, _openScopes.size());

        if (forcedExitFailure != null) {
            throwUnchecked(forcedExitFailure);
        }
    }

    /**
     * Exits, innermost first, the scopes opened inside {@code scope} that are still open: they were never completed, so their
     * work cannot be committed. The transaction is marked rollback-only and its rollback actions run (see
     * {@link #runRollbackActionsUnlessParticipating(Throwable)}). Every leaked scope is exited even if restoring an isolation
     * level fails; the first unchecked failure propagates afterwards, or else an {@link Error} thrown by a rollback action.
     *
     * @param scope an open scope
     * @return {@code true} if there were such scopes
     * @throws IllegalStateException if {@code scope} is not open
     */
    private boolean exitLeakedScopesAbove(final SqlTransaction scope) throws IllegalStateException {
        if (_openScopes.peek() == scope) { // the common case: the scope is the innermost one
            return false;
        }

        if (!_openScopes.contains(scope)) {
            throw new IllegalStateException("Transaction(id=" + _timedId + "): the scope is not open");
        }

        Throwable failure = null;

        while (_openScopes.peek() != scope) {
            final SqlTransaction leaked = _openScopes.peek();

            logger.warn("A nested scope of transaction(id={}) was never completed (no commit() or rollbackIfNotCommitted()/close() call); it is"
                    + " rolled back and the transaction is marked for rollback", _timedId);

            // Its own commit() must not report its work as committed later: see commit(SqlTransaction).
            leaked.markExitedByEnclosingScope();

            if (_status == Status.ACTIVE) {
                _status = Status.MARKED_ROLLBACK;
            }

            try {
                exitScope(leaked, true);
            } catch (final RuntimeException | Error e) {
                if (failure == null) {
                    failure = e;
                } else if (failure != e) {
                    failure.addSuppressed(e);
                }
            }
        }

        // A rollback-only transaction can only end rolled back: undo the in-memory changes tied to its work right away (unless
        // it participates in a Spring-managed transaction, which decides the outcome).
        final Error actionError = runRollbackActionsUnlessParticipating(failure);

        if (failure != null) {
            throwUnchecked(failure);
        }

        if (actionError != null) {
            throw actionError;
        }

        return true;
    }

    /**
     * Returns the failure of a {@code commit()} that found scopes opened inside the committing scope still open, according to
     * the transaction's status: a {@link RollbackOnlyException} once the outermost scope has rolled the transaction back (or
     * marked the Spring-managed transaction it participates in rollback-only), and an {@link IllegalStateException} while the
     * transaction is only marked for rollback (the committing scope is a nested one) or after the outermost scope failed to
     * roll it back.
     *
     * @return the failure to throw, or to attach to the failure thrown instead
     */
    private IllegalStateException leakedScopeException() {
        final String message = "Transaction(id=" + _timedId + ") cannot be committed: a scope opened inside the committing scope was never completed"
                + " (a commit() or rollbackIfNotCommitted()/close() call is missing, e.g. no finally block); the transaction " + rollbackOutcome();

        return _status == Status.ROLLED_BACK ? new RollbackOnlyException(message) : new IllegalStateException(message);
    }

    /**
     * Describes what has become of a transaction whose work can't be committed, according to its status: rolled back by the
     * outermost scope (for a participating transaction: to be rolled back by the Spring-managed transaction, which the
     * outermost scope marked rollback-only), not rolled back because that rollback (or mark) failed, not committed by the
     * outermost scope (for a participating transaction: the Spring-managed transaction had completed before it, see
     * {@link Status#FAILED_COMMIT}), or marked for rollback while the outermost scope is still open.
     *
     * @return the description, to follow "the transaction " in a message
     */
    private String rollbackOutcome() {
        if (_status == Status.ROLLED_BACK) {
            // A participating transaction's rollback only marks the Spring transaction, which rolls back when it completes.
            return _springParticipating ? "will be rolled back by the Spring-managed transaction it participates in, which has been marked rollback-only"
                    : "has been rolled back";
        }

        if (_status == Status.FAILED_COMMIT) {
            // WHY: a commit() after the outermost one had failed this way said that the transaction "is marked for rollback".
            return _springParticipating
                    ? "could not be committed: the Spring-managed transaction it participates in had completed before its commit, and decided the"
                            + " outcome of its work"
                    : "could not be committed";
        }

        return _status == Status.FAILED_ROLLBACK ? "could not be rolled back" : "is marked for rollback";
    }

    /**
     * Returns the failure of an outermost {@code commit()} that rolled back a transaction a nested scope marked rollback-only,
     * according to the outcome of that rollback: a {@link RollbackOnlyException} once the transaction has been rolled back (for
     * a participating transaction: once the Spring-managed transaction has been marked rollback-only, which then rolls back the
     * work), and an {@link IllegalStateException} if it could not be.
     *
     * @return the failure to throw, or to attach to the failure of the rollback (or of the cleanup after it) thrown instead
     */
    private IllegalStateException rollbackOnlyException() {
        final String reason = "a nested scope marked it rollback-only (typically, the failure of a nested transactional operation was caught and the"
                + " work went on)";

        if (_status != Status.ROLLED_BACK) {
            return new IllegalStateException("Transaction(id=" + _timedId + ") cannot be committed: " + reason + "; the transaction could not be rolled back");
        }

        // The rollback of a participating transaction marks the Spring transaction, which rolls back when it completes: saying
        // that the transaction "has been rolled back" claimed more than had happened.
        return _springParticipating
                ? new RollbackOnlyException("Transaction(id=" + _timedId + ") cannot be committed: " + reason + "; the transaction " + rollbackOutcome())
                : new RollbackOnlyException("Transaction(id=" + _timedId + ") has been rolled back instead of committed: " + reason);
    }

    /**
     * Exits the scopes opened inside {@code scope} that are still open (see {@link #exitLeakedScopesAbove(SqlTransaction)}),
     * returning the unchecked failure of a forced exit (or the {@link Error} of a rollback action) instead of throwing it, so
     * that the caller can still complete {@code scope}: every leaked scope has been exited either way.
     *
     * @param scope an open scope
     * @param leaked receives whether there were such scopes
     * @return the failure of a forced exit or of a rollback action, or {@code null}
     */
    private Throwable exitLeakedScopesAboveCollectingFailure(final SqlTransaction scope, final boolean[] leaked) {
        try {
            leaked[0] = exitLeakedScopesAbove(scope);
            return null;
        } catch (final RuntimeException | Error e) {
            leaked[0] = true;
            return e;
        }
    }

    /**
     * Exits {@code scope} as {@link #exitScope(SqlTransaction, boolean)} does without forcing, attaching
     * {@code leakExitFailure} to a failure of the exit (which leaves the scope open for its cleanup call to retry).
     */
    private void exitScopeAfterLeaks(final SqlTransaction scope, final Throwable leakExitFailure) {
        try {
            exitScope(scope, false);
        } catch (final RuntimeException | Error e) {
            if (leakExitFailure != null && leakExitFailure != e) {
                e.addSuppressed(leakExitFailure);
            }

            throw e;
        }
    }

    /**
     * Commits {@code scope}: see {@link SqlTransaction#commit()}.
     *
     * @param scope the scope to commit
     */
    void commit(final SqlTransaction scope) throws IllegalStateException, UncheckedSQLException {
        assertOwnerThread();

        if (scope.isCompleted()) {
            if (scope.isExitedByEnclosingScope()) {
                // An enclosing scope completed first and exited this one: its work can't be committed, so returning normally would
                // report it as committed. The outcome is worded after the status: it used to follow whether scopes were still open,
                // which reported a transaction whose rollback (or Spring mark) had failed as rolled back.
                throw new IllegalStateException("Transaction(id=" + _timedId + ") scope cannot be committed: an enclosing scope completed while it was"
                        + " still open and exited it; the transaction " + rollbackOutcome());
            }

            if (scope.isOutermost() ? _status == Status.COMMITTED : scope.isCommitted()) {
                // As in Spring: a repeated commit() was ignored, which hid the bug of calling it twice, and for a nested scope
                // returned normally even after the transaction had rolled back the scope's work.
                throw new IllegalStateException("Transaction(id=" + _timedId + ") scope has already been committed (status: " + _status
                        + "): commit() may be called only once per scope");
            }

            // The scope ended without a commit: it was rolled back (rollback(), rollbackIfNotCommitted() or close()), or its commit
            // failed (RollbackOnlyException, a failed database commit). Returning normally reported its work as committed.
            throw new IllegalStateException("Transaction(id=" + _timedId + ") scope cannot be committed: it has already ended without a commit (it was"
                    + " rolled back, or its commit failed); the transaction " + rollbackOutcome());
        }

        // Scopes opened inside this one and still open were never completed: their work can't be committed.
        final boolean[] leaked = { false };
        final Throwable leakExitFailure = exitLeakedScopesAboveCollectingFailure(scope, leaked);

        // Throws, leaving the scope open for its cleanup call to retry, if a nested exit cannot restore the isolation level.
        try {
            exitScopeAfterLeaks(scope, leakExitFailure);
        } catch (final RuntimeException | Error e) {
            if (leaked[0]) { // say why the transaction is now rollback-only, too
                e.addSuppressed(leakedScopeException());
            }

            throw e;
        }

        scope.markCompleted();

        if (leaked[0]) {
            // An unchecked driver failure while exiting a leaked scope takes precedence.
            Throwable failure = leakExitFailure;

            if (scope.isOutermost()) {
                try {
                    executeRollback();
                } catch (final RuntimeException | Error e) {
                    // A failed rollback (or Spring mark) propagates its own failure, as for a transaction marked rollback-only
                    // below. It used to be attached, an Error too, to a RollbackOnlyException, which reports the transaction as
                    // rolled back although its status is FAILED_ROLLBACK (Spring may still commit a participating one's work).
                    if (failure == null) {
                        failure = e;
                    } else if (failure != e) {
                        failure.addSuppressed(e);
                    }
                }
            }

            // Says why the commit failed; created after the rollback, so its type and message follow the outcome.
            final IllegalStateException leakFailure = leakedScopeException();

            if (failure == null) {
                failure = leakFailure;
            } else {
                failure.addSuppressed(leakFailure);
            }

            throwUnchecked(failure);
        }

        if (!scope.isOutermost()) {
            scope.markCommitted();
            logger.debug("Deferred commit for nested transaction(id={}); remaining scopes={}", _timedId, _openScopes.size());
            return;
        }

        if (_status == Status.MARKED_ROLLBACK) {
            logger.warn("Transaction(id={}) will be rolled back because it is marked for rollback only", _timedId);

            try {
                executeRollback();
            } catch (final RuntimeException | Error e) {
                // A failed rollback (or Spring mark) propagates its own failure, and so does a cleanup that fails after the rollback.
                // Why the commit failed is attached, worded after the recorded outcome, as for a leaked scope above: it used to be
                // missing, and a cleanup failure did not say that the transaction had been rolled back.
                e.addSuppressed(rollbackOnlyException());
                throw e;
            }

            // Otherwise the caller must learn that nothing was committed.
            throw rollbackOnlyException();
        }

        // No check that the status is ACTIVE here: while a scope is open, the transaction is ACTIVE or MARKED_ROLLBACK (every
        // completion exits the scopes before it changes the status), and the check, made after this scope had been exited,
        // would have left the connection unreleased.

        if (_springParticipating) {
            if (hasParticipatedTransactionCompleted()) {
                // WHY: a scope left open past the Spring transaction it participates in (no commit() before that transaction
                // completed, or before its completion callbacks, where Spring still binds its connection) reported COMMITTED,
                // while Spring had decided the outcome of its work without it, and may have rolled it back. Its rollback fails
                // here as well (see executeRollback); its commit must not claim success.
                _status = Status.FAILED_COMMIT;
                discardLocalRollbackActions("the Spring-managed transaction it participates in has completed");

                final IllegalStateException failure = new IllegalStateException("Transaction(id=" + _timedId + ") cannot be committed because "
                        + springCompletionReason() + "; its work may already have been rolled back");

                complete(failure);
                throw failure;
            }

            // The work stays in the Spring-managed transaction, which commits (or rolls back) the connection when it completes.
            _status = Status.COMMITTED;

            // Recorded here only when Spring's transaction synchronization was not active: its outcome can't be observed.
            discardLocalRollbackActions(
                    "Spring transaction synchronization is not active, so they can't run if the Spring-managed transaction" + " is rolled back");
            logger.info("Completed transaction(id={}); the Spring-managed transaction it participates in decides its outcome", _timedId);

            complete(null);
            return;
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
                _rollbackActions = null;

                // Run before the connection cleanup, whose failure must not keep them from running: the work is committed.
                final Error commitActionError = runCommitActions();

                complete(commitActionError);

                if (commitActionError != null) {
                    throw commitActionError;
                }
            } else {
                if (commitException == null) {
                    logger.warn("Commit did not complete transaction(id={}). Automatically rolling back", _timedId);
                } else {
                    logger.warn(commitException, "Failed to commit transaction(id={}). Automatically rolling back", _timedId);
                }

                // The rollback discards the commit actions, though the failed commit may have committed: the database committed, and
                // only its acknowledgement was lost (the rollback then undoes nothing). They run all the same (see
                // JdbcUtil.runAfterCommit: cache invalidations, safe to run once too often); dropped, they left caches holding
                // entries the commit had made stale. The rollback actions run with the rollback, as a failed commit usually rolled
                // back (a constraint checked at commit, a serialization failure): the generated IDs are put back also in the rare
                // case of a lost acknowledgement, whose rows exist (see SqlTransaction, scenario 15).
                final Map<Object, Runnable> uncertainCommitActions = _commitActions;

                try {
                    executeRollback();
                } catch (final RuntimeException | Error rollbackEx) {
                    if (commitFailure != null && commitFailure != rollbackEx) {
                        commitFailure.addSuppressed(rollbackEx);
                    }

                    logger.warn(rollbackEx,
                            "Failed to roll back transaction(id={}) after failed commit. Rollback error suppressed to preserve original commit exception.",
                            _timedId);
                } finally {
                    _commitActions = uncertainCommitActions;
                    final Error commitActionError = runCommitActions();

                    if (commitActionError != null && commitFailure != null && commitFailure != commitActionError) {
                        commitFailure.addSuppressed(commitActionError);
                    }
                }
            }
        }
    }

    /**
     * Rolls back {@code scope}: see {@link SqlTransaction#rollback()}.
     *
     * @param scope the scope to roll back
     */
    void rollback(final SqlTransaction scope) throws IllegalStateException, UncheckedSQLException {
        assertOwnerThread();

        if (scope.isCompleted()) {
            // As in Spring, and as commit(): a repeated rollback() was ignored, which hid the bug of completing a scope twice (and
            // a rollback() after a commit() returned normally, as if it had undone the scope's work). The cleanup calls,
            // rollbackIfNotCommitted() and close(), stay no-ops on a completed scope.
            throw new IllegalStateException("Transaction(id=" + _timedId + ") scope has already completed (status: " + _status
                    + "): rollback() may be called only on a scope that has not completed; use rollbackIfNotCommitted() or close() to clean up");
        }

        // WHY the same path as rollbackIfNotCommitted(): a nested rollback() whose exit could not restore the enclosing
        // isolation level threw and left the scope open, the transaction ACTIVE after an explicit rollback, while a nested
        // scope that rolls back dooms the transaction anyway, so keeping it open for a retry bought nothing.
        endWithoutCommit(scope);
    }

    /**
     * Ends {@code scope} unless it has completed: see {@link SqlTransaction#rollbackIfNotCommitted()}.
     *
     * @param scope the scope to end
     */
    void rollbackIfNotCommitted(final SqlTransaction scope) throws IllegalStateException, UncheckedSQLException {
        assertOwnerThread();

        if (scope.isCompleted()) {
            return;
        }

        // A scope that has not completed is open, so the transaction is ACTIVE or MARKED_ROLLBACK: a check for a completed
        // transaction here, which marked the scope completed and returned, could only have left it open and registered.
        endWithoutCommit(scope);
    }

    /**
     * Ends the open {@code scope} without a commit. The scope is always exited: if the enclosing scope's isolation level cannot
     * be restored on the connection, the transaction is marked rollback-only all the same; an {@link SQLException} from the
     * connection is logged, while a {@link RuntimeException} or an {@link Error} is propagated after the scope has been exited
     * (and, for the outermost scope, rolled back). Scopes opened inside it that are still open are exited first. A nested
     * scope marks the transaction rollback-only; the outermost scope rolls it back.
     *
     * @param scope the open scope to end
     */
    private void endWithoutCommit(final SqlTransaction scope) throws UncheckedSQLException {
        // The cleanup path must always make progress: every scope is exited, even when an isolation level cannot be restored.
        Throwable failure = exitLeakedScopesAboveCollectingFailure(scope, new boolean[1]);

        try {
            exitScope(scope, true);
        } catch (final RuntimeException | Error e) {
            if (failure == null) {
                failure = e;
            } else if (failure != e) {
                failure.addSuppressed(e);
            }
        }

        scope.markCompleted();

        if (!scope.isOutermost()) {
            _status = Status.MARKED_ROLLBACK;
            logger.warn("Marked nested transaction(id={}) for rollback; remaining scopes={}", _timedId, _openScopes.size());
            // A rollback-only transaction can only end rolled back: undo the in-memory changes tied to its work right away (unless
            // it participates in a Spring-managed transaction, which decides the outcome).
            final Error actionError = runRollbackActionsUnlessParticipating(failure);

            if (failure != null) {
                throwUnchecked(failure);
            }

            if (actionError != null) {
                throw actionError;
            }

            return;
        }

        if (failure == null) {
            executeRollback();
            return;
        }

        try {
            executeRollback();
        } catch (final RuntimeException | Error e) {
            if (e != failure) {
                failure.addSuppressed(e);
            }
        }

        throwUnchecked(failure);
    }

    /**
     * Executes the rollback of the outermost scope and cleans up the connection. A participating transaction marks the Spring
     * transaction it participates in rollback-only instead of rolling back the connection; a mark that fails, does not reach
     * every transaction deciding the outcome ({@link JdbcUtil#markSpringTransactionRollbackOnly}), or can't change it any more
     * (the completion of the Spring transaction has started, see {@link #hasParticipatedTransactionCompleted()}), leaves the
     * status {@link Status#FAILED_ROLLBACK} and propagates. An {@link Error} thrown by a rollback action after a successful rollback
     * (or mark), also one Spring's transaction synchronization runs once the mark is set, propagates once the connection has
     * been cleaned up.
     *
     * @throws UncheckedSQLException if the JDBC connection rejects the rollback
     */
    private void executeRollback() throws UncheckedSQLException {
        final Status previousStatus = _status;
        _commitActions = null;

        if (_springParticipating) {
            // Rolling back the connection would discard the Spring transaction's work done before this scope, and Spring would
            // then commit whatever follows. Mark the Spring transaction rollback-only instead: it rolls back when it completes.
            logger.warn("Marking the Spring-managed transaction rollback-only for transaction(id={}, status={})", _timedId, previousStatus);

            Throwable markFailure = null;
            Error actionError = null;

            try {
                if (_springOwner.isDeciding()) {
                    // WHY: the rollback of a scope left open into the completion callbacks of the Spring transaction (where Spring
                    // still binds its connection) marked it, which had no effect any more, and reported ROLLED_BACK, also after
                    // Spring had committed the work. Once the callbacks have run, a completed transaction's holder is no longer
                    // bound (or no longer transaction-active), which the marks below find.
                    throw new IllegalStateException("Cannot mark the Spring-managed transaction that transaction(id=" + _timedId
                            + ") participates in rollback-only because " + springCompletionReason() + "; the mark may no longer change the outcome");
                }

                if (isParticipatedResourceBound()) {
                    // WHY the result: the Error of a rollback action that Spring's synchronization ran once the mark was set was only
                    // logged, while one from an action recorded here propagates.
                    actionError = JdbcUtil.markSpringTransactionRollbackOnly(_ds);
                } else {
                    // The transaction participated in, not the one Spring binds now: completed while Spring runs another transaction
                    // on the data source (REQUIRES_NEW), this marked that one instead, and the one participated in could commit.
                    JdbcUtil.markUnboundSpringTransactionRollbackOnly(_springResource);
                }
            } catch (final RuntimeException | Error e) {
                markFailure = e;
                throw e;
            } finally {
                if (markFailure == null) {
                    _status = Status.ROLLED_BACK;
                    // Also the actions of nested scopes that rolled back before (see runRollbackActionsUnlessParticipating).
                    actionError = withSuppressed(actionError, runRollbackActions(null));
                } else {
                    // The mark failed, or did not reach every transaction that decides the work's outcome (no connection holder bound
                    // any more, a JPA owner that refused it): Spring may still commit the work. A mark that reached no JPA owner used
                    // to count as done: ROLLED_BACK, no exception, and a commit() claiming a rollback, while Spring committed the work.
                    _status = Status.FAILED_ROLLBACK;
                    logger.warn(markFailure, "Failed to mark the Spring-managed transaction rollback-only for transaction(id={}); it may still"
                            + " commit the work, so the in-memory changes tied to this transaction are left to its outcome", _timedId);

                    // Actions recorded here (synchronization not active) can't follow that outcome: discarded. They used to run after
                    // a mark that reached no JPA owner, undoing the changes of work that Spring then committed.
                    discardLocalRollbackActions(
                            "Spring transaction synchronization is not active, so they can't follow the Spring-managed" + " transaction's outcome");
                }

                // The Error of a rollback action propagates once the connection has been released, with any cleanup failure attached.
                complete(markFailure != null ? markFailure : actionError);
            }

            if (actionError != null) {
                throw actionError;
            }

            return;
        }

        logger.info("Rolling back transaction(id={}, status={})", _timedId, previousStatus);

        _status = Status.FAILED_ROLLBACK;
        SQLException rollbackException = null;
        Throwable rollbackFailure = null;
        Error actionError = null;

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

            // Also after a failed ROLLBACK: the work was never committed, so in-memory changes tied to it are undone either way.
            actionError = runRollbackActions(rollbackFailure);
            // The Error of a rollback action propagates once the connection has been released, with any cleanup failure attached.
            complete(rollbackFailure != null ? rollbackFailure : actionError);
        }

        if (actionError != null) {
            throw actionError;
        }
    }

    /**
     * Registers an action to run if this transaction does not end with a successful commit: when the transaction is marked
     * rollback-only (a nested scope rolls back, or is exited by its enclosing scope because it was never completed: the
     * transaction can then only end rolled back), or when the outermost scope rolls back. Each action runs at most once,
     * newest first. In a participating transaction, the action is registered with Spring's transaction synchronization when
     * it is active; otherwise it runs only once the outermost scope has marked the Spring-managed transaction rollback-only,
     * and is discarded if that mark fails or the outermost scope commits (Spring may then commit the work).
     *
     * @param action the action to run; ignored if {@code null}
     * @throws IllegalStateException if called from a thread other than the transaction's owner thread
     */
    void addRollbackAction(final Runnable action) throws IllegalStateException {
        assertOwnerThread();

        if (action == null) {
            return;
        }

        if (_springParticipating && JdbcUtil.registerSpringRollbackAction(_ds, action)) {
            return;
        }

        if (_rollbackActions == null) {
            _rollbackActions = new RollbackActionLog();
        }

        _rollbackActions.add(action);
    }

    /**
     * Registers an action to run once this transaction's outermost scope has committed, before the connection cleanup, or
     * after its commit failed (the database may have committed: see {@link JdbcUtil#runAfterCommit(javax.sql.DataSource,
     * Object, Runnable)}); it is discarded if the transaction rolls back without attempting a commit. A failing action is
     * logged and does not stop the others; an {@link Error} thrown by one propagates from the outermost {@code commit()} once
     * the transaction has completed (attached to the commit's failure, after a failed commit).
     *
     * @param key identifies the action: an action whose key equals that of one registered before is not registered (it does
     *        the same); {@code null} to register it in any case
     * @param action the action to run
     * @throws IllegalStateException if called from a thread other than the transaction's owner thread
     */
    void addCommitAction(final Object key, final Runnable action) throws IllegalStateException {
        assertOwnerThread();

        if (_commitActions == null) {
            _commitActions = new LinkedHashMap<>(4);
        }

        _commitActions.putIfAbsent(key == null ? new Object() : key, action);
    }

    /**
     * Runs (and discards) the registered commit actions, in registration order; see {@link #addCommitAction(Object, Runnable)}.
     * A failing action does not stop the others: a {@code RuntimeException} is logged, while an {@link Error} is returned, not
     * logged, for the caller to throw.
     *
     * @return the first {@link Error} thrown by an action (later ones attached to it), or {@code null}
     */
    private Error runCommitActions() {
        final Map<Object, Runnable> actions = _commitActions;
        _commitActions = null;

        if (actions == null) {
            return null;
        }

        Error firstError = null;

        for (final Runnable action : actions.values()) {
            try {
                action.run();
            } catch (final Error e) {
                // Thrown by the caller: logged as well, it was reported twice.
                if (firstError == null) {
                    firstError = e;
                } else if (firstError != e) {
                    firstError.addSuppressed(e);
                }
            } catch (final RuntimeException e) {
                logger.warn(e, "Failed to run an action after the commit of transaction(id={})", _timedId);
            }
        }

        return firstError;
    }

    /**
     * Runs (and discards) the registered rollback actions, newest first. A failing action is logged, attached to
     * {@code primaryFailure} if there is one, and does not stop the remaining actions. Without a primary failure, the first
     * {@link Error} thrown by an action (later ones attached to it) is returned for the caller to throw once it has completed
     * the scope; an exception is only logged, as the rollback itself succeeded.
     *
     * @param primaryFailure the failure already propagating, or {@code null}
     * @return the first {@code Error} thrown by an action if {@code primaryFailure} is {@code null}; otherwise {@code null}
     */
    private Error runRollbackActions(final Throwable primaryFailure) {
        final RollbackActionLog actions = _rollbackActions;
        _rollbackActions = null;

        if (actions == null) {
            return null;
        }

        final Error[] firstError = { null };

        actions.runNewestFirst(e -> {
            if (primaryFailure != null) {
                if (primaryFailure != e) {
                    primaryFailure.addSuppressed(e);
                }
            } else if (e instanceof final Error error) {
                // An Error (OutOfMemoryError, LinkageError, ...) used to be logged only, like an exception, when nothing else failed.
                if (firstError[0] == null) {
                    firstError[0] = error;
                } else if (firstError[0] != error) {
                    firstError[0].addSuppressed(error);
                }
            }

            logger.warn(e, "Failed to run a rollback action of transaction(id={})", _timedId);
        });

        return firstError[0];
    }

    /**
     * Runs the rollback actions once a nested scope has marked the transaction rollback-only, unless the transaction
     * participates in a Spring-managed transaction. A participating transaction leaves them to its outermost scope, whose
     * rollback marks the Spring transaction rollback-only and runs them only once that mark is set: until then Spring decides
     * the outcome, and if the mark fails it may still commit the work. They used to run here, before the mark was even
     * attempted, so a failed mark left entities without the IDs of rows Spring committed.
     *
     * @param primaryFailure the failure already propagating, or {@code null}
     * @return the first {@code Error} thrown by an action if {@code primaryFailure} is {@code null}, for the caller to throw once
     *         it has completed the scope (see {@link #runRollbackActions(Throwable)}); otherwise {@code null}
     */
    private Error runRollbackActionsUnlessParticipating(final Throwable primaryFailure) {
        return _springParticipating ? null : runRollbackActions(primaryFailure);
    }

    /**
     * Attempts the connection cleanup, attaching its failure to {@code primaryFailure} if there is one, or else throwing it.
     *
     * @param primaryFailure an already propagating failure, or {@code null}
     */
    private void complete(final Throwable primaryFailure) {
        try {
            resetAndCloseConnection();
        } catch (final RuntimeException | Error e) {
            if (primaryFailure == null) {
                throw e;
            }

            if (primaryFailure != e) {
                primaryFailure.addSuppressed(e);
            }
        }
    }

    /**
     * Discards the rollback actions this transaction recorded itself, logging that they won't run, and why.
     *
     * @param reason why they can't run, to follow "Discarded n rollback action(s) of transaction(id=...): "
     */
    private void discardLocalRollbackActions(final String reason) {
        if (_rollbackActions != null && _rollbackActions.size() > 0) {
            logger.warn("Discarded {} rollback action(s) of transaction(id={}): {}", _rollbackActions.size(), _timedId, reason);
        }

        _rollbackActions = null;
    }

    /**
     * Returns {@code first}, with {@code next} attached to it, or {@code next} if {@code first} is {@code null}.
     */
    private static Error withSuppressed(final Error first, final Error next) {
        if (first == null) {
            return next;
        }

        if (next != null && next != first) {
            first.addSuppressed(next);
        }

        return first;
    }

    /**
     * Restores the connection's original auto-commit mode and isolation level and releases it if this transaction owns it, the
     * way it was acquired. After a failed rollback, state restoration is skipped to avoid implicitly committing pending work,
     * and an owned connection is aborted before it is released (released also if the abort fails), and taken from Spring if
     * Spring bound it to the thread for synchronization (see {@link JdbcUtil#detachAbortedSpringConnection}). A participating
     * transaction's connection is left to Spring while Spring binds another resource to the data source, or none (see
     * {@link JdbcUtil#releaseUnboundParticipatingConnection}). Called after commit or rollback, and by
     * {@link JdbcUtil#beginTransaction} when opening the first scope fails.
     */
    void resetAndCloseConnection() {
        if (_springParticipating) {
            // Nothing was changed on the connection, and Spring's transaction completion resets and releases it.
            if (isParticipatedResourceBound()) {
                JdbcUtil.releaseSpringManagedConnection(_conn, _ds);
            } else {
                // Spring binds another resource to the data source, or none: released through DataSourceUtils, the connection
                // (not that resource's) was closed, while the transaction participated in still used it after Spring resumed it.
                JdbcUtil.releaseUnboundParticipatingConnection(_ds, _springResource);
            }

            return;
        }

        if (_status == Status.FAILED_ROLLBACK) {
            if (_closeConnection) {
                // The connection may still hold the transaction's work. Released as it is, it went back to the pool with that
                // work pending, and a pool that restores auto-commit on its connections committed it (setAutoCommit(true) commits
                // the current transaction). Aborted, it is discarded instead.
                try {
                    abortConnection();
                } finally {
                    // Also if the abort throws an Error: the connection was never closed, and kept the work and its locks.
                    releaseConnection();

                    if (_acquiredThroughSpring) {
                        // Spring keeps a connection it bound to the thread for synchronization (a SUPPORTS scope, or a Spring
                        // transaction on another data source) for the rest of its scope: later work there got the aborted
                        // connection and failed on it. Taken from Spring (and closed), it is replaced by a new one for that work.
                        JdbcUtil.detachAbortedSpringConnection(_conn, _ds);
                    }
                }
            }

            return;
        }

        Throwable cleanupFailure = null;

        // Restore both original settings even if the constructor left them unchanged: callers can modify the exposed connection.
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
            } else if (cleanupFailure != e) {
                cleanupFailure.addSuppressed(e);
            }
        }

        if (_closeConnection) {
            logger.debug("Releasing connection for transaction(id={})", _timedId);

            try {
                releaseConnection();
            } catch (final RuntimeException | Error e) {
                if (cleanupFailure == null) {
                    cleanupFailure = e;
                } else if (cleanupFailure != e) {
                    cleanupFailure.addSuppressed(e);
                }
            }
        }

        if (cleanupFailure != null) {
            throwUnchecked(cleanupFailure);
        }
    }

    /**
     * Returns whether Spring binds to the data source the resource of the transaction this one participates in (see
     * {@link #getTransaction(javax.sql.DataSource, CreatedBy)}); always {@code true} for a transaction that does not participate.
     *
     * @return {@code false} while Spring runs another transaction on the data source, or none
     */
    private boolean isParticipatedResourceBound() {
        return !_springParticipating || JdbcUtil.boundSpringResource(_ds) == _springResource;
    }

    /**
     * Checks the exact owner captured at participation, also from a deferred query's worker thread. Synchronization order
     * cannot identify completion reliably: registration during beforeCompletion misses its snapshot, and a worker cannot
     * read the owner's Spring thread-local phase. Mandatory tracking publishes the owner's decision before callbacks run.
     *
     * @return whether the owning transaction is deciding its outcome or has completed
     */
    private boolean hasParticipatedTransactionCompleted() {
        return _springOwner.isDeciding();
    }

    /** Describes the captured owner's completion, independent of another manager's synchronization on this thread. */
    private String springCompletionReason() {
        return "the owning Spring-managed transaction has completed or runs its completion callbacks";
    }

    /**
     * Removes {@code exited}, whose outermost scope has exited, from the chain of transactions this registered one heads (it
     * and those it hides, see {@link #putTransaction}), keeping the participating transaction {@code exited} hid, unless that
     * one has completed meanwhile.
     *
     * @param exited the transaction whose outermost scope has exited
     * @return the transaction to register in place of this one
     */
    private TransactionContext unlink(final TransactionContext exited) {
        final TransactionContext hidden = firstOpen(exited._shadowed);

        if (this == exited) {
            _shadowed = null;
            return hidden;
        }

        // WHY the walk: a hidden transaction exiting before the one hiding it (scopes completed out of order) only compared
        // itself with the registered one, and dropped the transaction it hid: still open, it was no longer found, so later work
        // began a transaction of its own instead of joining it.
        for (TransactionContext tran = this; tran != null; tran = tran._shadowed) {
            if (tran._shadowed == exited) {
                tran._shadowed = hidden;
                exited._shadowed = null;
                break;
            }
        }

        return this;
    }

    /**
     * Returns {@code tran}, or the first transaction down the chain it heads (see {@link #putTransaction}) whose outermost
     * scope is still open. WHY the walk: a transaction that completed while its chain could not be reached (its head
     * unregistered by its own {@code callOutsideTransaction}) stays in the chain; looking one level down only, the open
     * transaction it hid was dropped, so later work began a transaction of its own instead of joining it.
     *
     * @param tran the transaction to start from, or {@code null}
     * @return the first open transaction, or {@code null} if none is
     */
    private static TransactionContext firstOpen(TransactionContext tran) {
        while (tran != null && tran.depth() == 0) {
            tran = tran._shadowed;
        }

        return tran;
    }

    /**
     * Releases the connection the way it was acquired. The thread's Spring setting may have changed since the transaction
     * began (it completes inside {@link JdbcUtil#runIgnoringSpringTransaction(Throwables.Runnable)}, or began in one): read
     * at completion, it made this transaction close a connection that Spring had bound to the thread when it was acquired
     * (for synchronization, in a {@code SUPPORTS} scope or while a Spring transaction runs on another data source), which
     * Spring's synchronization still used.
     */
    @SuppressWarnings("deprecation")
    private void releaseConnection() {
        if (_acquiredThroughSpring) {
            JdbcUtil.releaseSpringManagedConnection(_conn, _ds);
        } else {
            JdbcUtil.closeQuietly(_conn);
        }
    }

    /**
     * Aborts the connection ({@link Connection#abort(java.util.concurrent.Executor)}), so that a pool discards it; a failure,
     * such as a driver that does not support it, is logged. That includes a {@link LinkageError}: a driver written for a JDBC
     * version before 4.1 throws {@link AbstractMethodError} (jTDS, for one).
     */
    private void abortConnection() {
        try {
            _conn.abort(Runnable::run);
        } catch (final SQLException | RuntimeException | LinkageError e) {
            logger.warn(e, "Failed to abort the connection of transaction(id={}) after its rollback failed", _timedId);
        }
    }

    /**
     * Maps a scope's isolation level to the JDBC constant the connection runs at: {@link IsolationLevel#DEFAULT} resolves to
     * the level captured when this transaction was created.
     */
    private int toConnectionIsolationLevel(final IsolationLevel isolationLevel) {
        return isolationLevel == IsolationLevel.DEFAULT ? _originalIsolationLevel : isolationLevel.intValue();
    }

    /**
     * Runs {@code cmd} with this transaction unregistered from the thread: see {@link SqlTransaction#runOutsideTransaction}.
     */
    <E extends Throwable> void runOutsideTransaction(final Throwables.Runnable<E> cmd) throws IllegalStateException, IllegalArgumentException, E {
        assertOwnerThread();
        N.checkArgNotNull(cmd, cs.cmd);

        callOutsideTransaction(() -> {
            cmd.run();
            return null;
        });
    }

    /**
     * Calls {@code cmd} with this transaction unregistered from the thread: see {@link SqlTransaction#callOutsideTransaction}.
     */
    <R, E extends Throwable> R callOutsideTransaction(final Throwables.Callable<? extends R, E> cmd) throws IllegalStateException, IllegalArgumentException, E {
        assertOwnerThread();
        N.checkArgNotNull(cmd, cs.cmd);

        // Leave Spring's transactions on the data source too, as JdbcUtil.callOutsideTransaction does: a participating
        // transaction's connection is Spring's, and while Spring's transaction synchronization is active (a SUPPORTS scope, or a
        // Spring transaction on another data source) Spring has bound this transaction's own connection to the thread as well.
        // Unless this transaction participated, cmd got that connection back, so its work ran in this transaction and rolled
        // back with it. Work on other data sources keeps joining their Spring transactions. Suspended before this transaction is
        // unregistered, whose registration nothing would restore if the suspension failed.
        final boolean leavesSpringTransaction = JdbcUtil.suspendSpringTransactional(_ds);

        // No monitor around cmd: the context is confined to its owner thread (checked above), so the one used here could never be
        // contended, while it pinned a virtual thread to its carrier for all of cmd's JDBC work (JDK 21 to 23).
        final boolean wasRegistered = threadTransactionMap.remove(_key, this);

        Throwable throwable = null;

        try {
            return cmd.call();
        } catch (final Throwable e) { //NOSONAR
            throwable = e;
            throw e;
        } finally {
            if (leavesSpringTransaction) {
                JdbcUtil.resumeSpringTransactional(_ds);
            }

            // Completed inside cmd, this transaction registers the participating one it hid instead (see exitScope).
            final TransactionContext toRegister = _status == Status.ACTIVE || _status == Status.MARKED_ROLLBACK ? this : firstOpen(_shadowed);

            if (wasRegistered) {
                // Nothing was registered under the key while cmd ran but what cmd began: one still registered was never completed.
                // WHY looked up whatever toRegister is: once cmd had completed this transaction too (nothing to register again),
                // the transaction cmd left open was not noticed, and stayed registered on the (pooled) thread.
                final IllegalStateException leakFailure = threadTransactionMap.get(_key) == null ? null
                        : new IllegalStateException(
                                "Another transaction is opened but not closed in 'SqlTransaction.callOutsideTransaction';" + " it has been rolled back"); //NOSONAR

                if (leakFailure != null) {
                    rollBackLeftOpen(leakFailure);
                }

                if (toRegister != null) {
                    threadTransactionMap.put(_key, toRegister);
                }

                // Only where it is registered: a nested call (this transaction not registered) cleared it, so the outer call
                // registered nothing, and the participating transaction this one hid was never found again.
                if (toRegister != this) {
                    _shadowed = null;
                }

                if (leakFailure != null) {
                    if (throwable != null) {
                        throwable.addSuppressed(leakFailure);
                    } else {
                        throw leakFailure;
                    }
                }
            }
        }
    }

    /**
     * Rolls back the transactions registered under this transaction's key that a command run outside it left open (see
     * {@link #callOutsideTransaction(Throwables.Callable)}): each ends as by {@code rollbackIfNotCommitted()} of its outermost
     * scope, its nested scopes exited first; a participating one marks its Spring-managed transaction rollback-only. A failure
     * is attached to {@code leakFailure}. WHY: reported only, such a transaction stayed registered in place of this one, so
     * the work on the data source meant for this transaction joined it, and its connection stayed held on the (pooled) thread.
     *
     * @param leakFailure the failure that reports the leak
     */
    private void rollBackLeftOpen(final IllegalStateException leakFailure) {
        for (TransactionContext leaked; (leaked = threadTransactionMap.get(_key)) != null;) {
            try {
                final SqlTransaction outermost = leaked._openScopes.peekLast();

                if (outermost != null) {
                    leaked.rollbackIfNotCommitted(outermost);
                }
            } catch (final RuntimeException | Error e) {
                leakFailure.addSuppressed(e);
            } finally {
                // Removed whatever its rollback did, so that the loop ends: a transaction it hid, registered again, is next.
                threadTransactionMap.remove(_key, leaked);
            }
        }
    }

    private static void throwUnchecked(final Throwable failure) {
        if (failure instanceof RuntimeException runtimeException) {
            throw runtimeException;
        }

        throw (Error) failure;
    }

    /**
     * Generates the diagnostic base ID: the data source's identity hash code, the current thread ID and the creator's ordinal.
     *
     * @param ds the data source; may be {@code null}
     * @param creator the transaction creator type, must not be {@code null}
     * @return the diagnostic transaction identifier
     */
    static String getTransactionId(final javax.sql.DataSource ds, final CreatedBy creator) throws IllegalArgumentException {
        N.checkArgNotNull(creator, cs.creator);

        return Strings.concat(System.identityHashCode(ds), "_", Thread.currentThread().threadId(), "_", creator.ordinal());
    }

    /**
     * Returns the transaction registered on the current thread for {@code ds} and {@code creator}. A transaction that
     * participates in a Spring-managed transaction is hidden while Spring binds another resource to the data source than the
     * one of the transaction it participates in: Spring runs another transaction on it ({@code REQUIRES_NEW}) or has
     * suspended its transaction ({@code NOT_SUPPORTED}), and the work there follows Spring's binding.
     *
     * @param ds the data source
     * @param creator the transaction creator type, must not be {@code null}
     * @return the active transaction, or {@code null} if none exists (or it is hidden)
     */
    static TransactionContext getTransaction(final javax.sql.DataSource ds, final CreatedBy creator) throws IllegalArgumentException {
        N.checkArgNotNull(creator, cs.creator);

        final TransactionContext tran = threadTransactionMap.get(new TransactionKey(ds, Thread.currentThread(), creator));

        // A participating transaction used to take the work of a Spring transaction begun inside it: work in a REQUIRES_NEW
        // scope ran on the connection of the suspended transaction and was rolled back with it, and work in a NOT_SUPPORTED
        // scope joined it.
        return tran == null || tran.isParticipatedResourceBound() ? tran : null;
    }

    /**
     * Registers {@code tran} on its owner thread. A transaction registered there before can only be a participating one
     * hidden from {@link #getTransaction(javax.sql.DataSource, CreatedBy)} (callers register a transaction only when that
     * method returns {@code null}): {@code tran} keeps it, and registers it again when its own outermost scope exits.
     *
     * @param tran the transaction to register, must not be {@code null}
     * @return the previously registered transaction for that thread and data source, or {@code null}
     */
    static TransactionContext putTransaction(final TransactionContext tran) {
        final TransactionContext previous = threadTransactionMap.put(tran._key, tran);

        tran._shadowed = previous;

        return previous;
    }

    @Override
    public String toString() {
        // No depth: logged for the transactions of every thread (see exitScope), whose open scopes only their owner thread may read.
        return "TransactionContext={id=" + _timedId + "}";
    }
}
