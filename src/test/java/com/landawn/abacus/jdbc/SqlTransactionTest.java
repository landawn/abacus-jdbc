package com.landawn.abacus.jdbc;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.lang.reflect.Field;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import javax.sql.DataSource;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentMatchers;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.MockedStatic;
import org.mockito.Mockito;
import org.mockito.MockitoAnnotations;

import com.landawn.abacus.TestBase;
import com.landawn.abacus.exception.UncheckedSQLException;

public class SqlTransactionTest extends TestBase {

    @Mock
    private DataSource dataSource;

    @Mock
    private Connection connection;

    private AutoCloseable mocks;

    @BeforeEach
    public void setUp() throws SQLException {
        mocks = MockitoAnnotations.openMocks(this);
        when(dataSource.getConnection()).thenReturn(connection);
        when(connection.getAutoCommit()).thenReturn(true);
        when(connection.getTransactionIsolation()).thenReturn(Connection.TRANSACTION_READ_COMMITTED);
    }

    // These unit fixtures mock Spring's marking/release operations and have no real resource binding. Supply the active
    // owner required by a participating context; integration tests exercise registration against actual Spring managers.
    private TransactionContext participatingContext(final Connection springConnection) throws SQLException {
        try (MockedStatic<SpringTransactionOwners> owners = Mockito.mockStatic(SpringTransactionOwners.class)) {
            owners.when(() -> SpringTransactionOwners.require(null)).thenReturn(new SpringTransactionOwners.Owner(() -> false, "unit fixture"));
            return new TransactionContext(dataSource, springConnection, IsolationLevel.DEFAULT, SqlTransaction.CreatedBy.JDBC_UTIL, true, true);
        }
    }

    @Test
    public void testId() throws SQLException {
        final SqlTransaction transaction = JdbcUtil.beginTransaction(dataSource, IsolationLevel.READ_COMMITTED);

        assertNotNull(transaction.id());
        assertTrue(transaction.id().contains(String.valueOf(System.identityHashCode(dataSource))));
    }

    @Test
    public void testIdsAreUniqueWhenTransactionsStartInSameClockTick() throws SQLException {
        final TransactionContext first = new TransactionContext(dataSource, connection, IsolationLevel.READ_COMMITTED, SqlTransaction.CreatedBy.JDBC_UTIL,
                false, false);
        final TransactionContext second = new TransactionContext(dataSource, connection, IsolationLevel.READ_COMMITTED, SqlTransaction.CreatedBy.JDBC_UTIL,
                false, false);

        assertNotEquals(first.id(), second.id());
    }

    @Test
    public void testConnection() throws SQLException {
        final SqlTransaction transaction = JdbcUtil.beginTransaction(dataSource, IsolationLevel.READ_COMMITTED);

        assertSame(connection, transaction.connection());
    }

    @SuppressWarnings("deprecation")
    @Test
    public void testThreadBoundOperationsRejectNonOwnerThread() throws Exception {
        final SqlTransaction transaction = JdbcUtil.beginTransaction(dataSource, IsolationLevel.READ_COMMITTED);
        final AtomicBoolean outsideCommandExecuted = new AtomicBoolean();
        final Runnable[] operations = { transaction::connection, transaction::commit, transaction::rollback, transaction::rollbackIfNotCommitted,
                transaction::close, () -> transaction.context().enterScope(IsolationLevel.READ_COMMITTED),
                () -> transaction.context().addRollbackAction(() -> outsideCommandExecuted.set(true)),
                () -> transaction.runOutsideTransaction(() -> outsideCommandExecuted.set(true)), () -> transaction.callOutsideTransaction(() -> {
                    outsideCommandExecuted.set(true);
                    return null;
                }) };

        try {
            for (int i = 0; i < operations.length; i++) {
                final int operationIndex = i;
                final AtomicReference<Throwable> failure = new AtomicReference<>();
                final Thread thread = new Thread(() -> {
                    try {
                        operations[operationIndex].run();
                    } catch (final Throwable e) {
                        failure.set(e);
                    }
                }, "foreign-transaction-thread-" + operationIndex);

                thread.start();
                thread.join();

                assertTrue(failure.get() instanceof IllegalStateException, "operation " + operationIndex + " must reject the foreign thread");
            }

            assertFalse(outsideCommandExecuted.get());
            assertSame(transaction.context(), TransactionContext.getTransaction(dataSource, SqlTransaction.CreatedBy.JDBC_UTIL));
            assertEquals(SqlTransaction.Status.ACTIVE, transaction.status());
        } finally {
            transaction.rollbackIfNotCommitted();
        }

        verify(connection).rollback();
    }

    @SuppressWarnings("deprecation")
    @Test
    public void testOwnerStateIsCheckedBeforeNullArguments() throws Exception {
        final SqlTransaction transaction = JdbcUtil.beginTransaction(dataSource, IsolationLevel.READ_COMMITTED);
        final Runnable[] operations = { transaction::commit, transaction::rollback, () -> transaction.context().enterScope(null),
                () -> transaction.runOutsideTransaction(null), () -> transaction.callOutsideTransaction(null) };

        try {
            for (final Runnable operation : operations) {
                final AtomicReference<Throwable> failure = new AtomicReference<>();
                final Thread thread = new Thread(() -> {
                    try {
                        operation.run();
                    } catch (final Throwable e) {
                        failure.set(e);
                    }
                });
                thread.start();
                thread.join();
                assertTrue(failure.get() instanceof IllegalStateException);
            }

            assertEquals(SqlTransaction.Status.ACTIVE, transaction.status());
            assertSame(transaction.context(), TransactionContext.getTransaction(dataSource, SqlTransaction.CreatedBy.JDBC_UTIL));
            verify(connection, never()).commit();
            verify(connection, never()).rollback();
        } finally {
            transaction.rollbackIfNotCommitted();
        }
    }

    @Test
    public void testCompletedTransactionStatePrecedesNestedIsolationValidation() throws SQLException {
        final SqlTransaction transaction = new TransactionContext(dataSource, connection, IsolationLevel.DEFAULT, SqlTransaction.CreatedBy.JDBC_UTIL, false,
                false).enterScope(IsolationLevel.DEFAULT);
        transaction.commit();

        assertThrows(IllegalStateException.class, () -> transaction.context().enterScope(null));
        assertEquals(SqlTransaction.Status.COMMITTED, transaction.status());
    }

    @Test
    public void testConstructorValidatesArgumentsInSignatureOrder() {
        // The message must name the declared parameter (ds), the same way JdbcUtil.beginTransaction(ds, ...) does.
        assertTrue(
                assertThrows(IllegalArgumentException.class, () -> new TransactionContext(null, null, null, null, true, false)).getMessage().contains("'ds'"));
        assertTrue(assertThrows(IllegalArgumentException.class, () -> new TransactionContext(null, connection, IsolationLevel.NONE, null, false, false))
                .getMessage()
                .contains("isolationLevel"));
        Mockito.verifyNoInteractions(connection);
    }

    @Test
    public void testIsolationLevel() throws SQLException {
        final SqlTransaction transaction = JdbcUtil.beginTransaction(dataSource, IsolationLevel.READ_COMMITTED);

        assertEquals(IsolationLevel.READ_COMMITTED, transaction.isolationLevel());
    }

    @Test
    public void testNestedDefaultIsolationInheritsOuterLevelAcrossDeeperScope() throws SQLException {
        when(connection.getTransactionIsolation()).thenReturn(Connection.TRANSACTION_READ_UNCOMMITTED);

        final SqlTransaction transaction = new TransactionContext(dataSource, connection, IsolationLevel.READ_COMMITTED, SqlTransaction.CreatedBy.JDBC_UTIL,
                false, false).enterScope(IsolationLevel.READ_COMMITTED);

        final SqlTransaction inheriting = transaction.context().enterScope(IsolationLevel.DEFAULT);
        assertEquals(IsolationLevel.READ_COMMITTED, inheriting.isolationLevel());

        final SqlTransaction serializable = transaction.context().enterScope(IsolationLevel.SERIALIZABLE);
        assertEquals(IsolationLevel.SERIALIZABLE, serializable.isolationLevel());

        serializable.commit();
        assertEquals(IsolationLevel.READ_COMMITTED, transaction.context().isolationLevel());
        verify(connection, times(2)).setTransactionIsolation(Connection.TRANSACTION_READ_COMMITTED);

        inheriting.rollbackIfNotCommitted();
        transaction.rollbackIfNotCommitted();
    }

    @Test
    public void testTransactionNoneIsRejectedBeforeConnectionMutation() throws SQLException {
        assertThrows(IllegalArgumentException.class,
                () -> new TransactionContext(dataSource, connection, IsolationLevel.NONE, SqlTransaction.CreatedBy.JDBC_UTIL, false, false));

        verify(connection, never()).setAutoCommit(false);
        verify(connection, never()).setTransactionIsolation(Connection.TRANSACTION_NONE);
    }

    @Test
    public void testNullCreatorIsRejectedBeforeConnectionMutation() throws SQLException {
        assertThrows(IllegalArgumentException.class, () -> new TransactionContext(dataSource, connection, IsolationLevel.READ_COMMITTED, null, false, false));

        verify(connection, never()).setAutoCommit(false);
    }

    @Test
    public void testConstructorMutationFailurePreservesCleanupFailures() throws SQLException {
        final SQLException isolationFailure = new SQLException("isolation rejected");
        final AssertionError autoCommitRestoreFailure = new AssertionError("auto-commit restore failed");
        final IllegalStateException isolationRestoreFailure = new IllegalStateException("isolation restore failed");
        doThrow(autoCommitRestoreFailure).when(connection).setAutoCommit(true);
        doThrow(isolationFailure).when(connection).setTransactionIsolation(IsolationLevel.SERIALIZABLE.intValue());
        doThrow(isolationRestoreFailure).when(connection).setTransactionIsolation(Connection.TRANSACTION_READ_COMMITTED);

        final SQLException thrown = assertThrows(SQLException.class,
                () -> new TransactionContext(dataSource, connection, IsolationLevel.SERIALIZABLE, SqlTransaction.CreatedBy.JDBC_UTIL, false, false));

        assertSame(isolationFailure, thrown);
        assertEquals(2, thrown.getSuppressed().length);
        assertSame(autoCommitRestoreFailure, thrown.getSuppressed()[0]);
        assertSame(isolationRestoreFailure, thrown.getSuppressed()[1]);
    }

    @Test
    public void testConstructorAutoCommitFailureRestoresOnlyAutoCommit() throws SQLException {
        // Isolation was never touched when setAutoCommit(false) fails, so recovery must not reapply it:
        // that would be the same redundant driver call the conditional mutations avoid.
        final SQLException autoCommitFailure = new SQLException("auto-commit rejected");
        doThrow(autoCommitFailure).when(connection).setAutoCommit(false);

        final SQLException thrown = assertThrows(SQLException.class,
                () -> new TransactionContext(dataSource, connection, IsolationLevel.SERIALIZABLE, SqlTransaction.CreatedBy.JDBC_UTIL, false, false));

        assertSame(autoCommitFailure, thrown);
        assertEquals(0, thrown.getSuppressed().length);
        verify(connection).setAutoCommit(true);
        verify(connection, never()).setTransactionIsolation(ArgumentMatchers.anyInt());
    }

    @Test
    public void testConstructorIsolationFailureRestoresOnlyIsolationWhenAutoCommitWasUnchanged() throws SQLException {
        when(connection.getAutoCommit()).thenReturn(false);
        final SQLException isolationFailure = new SQLException("isolation rejected");
        doThrow(isolationFailure).when(connection).setTransactionIsolation(IsolationLevel.SERIALIZABLE.intValue());

        final SQLException thrown = assertThrows(SQLException.class,
                () -> new TransactionContext(dataSource, connection, IsolationLevel.SERIALIZABLE, SqlTransaction.CreatedBy.JDBC_UTIL, false, false));

        assertSame(isolationFailure, thrown);
        assertEquals(0, thrown.getSuppressed().length);
        verify(connection, never()).setAutoCommit(ArgumentMatchers.anyBoolean());
        verify(connection).setTransactionIsolation(Connection.TRANSACTION_READ_COMMITTED);
    }

    @Test
    public void testConstructorKeepsExistingManualCommitMode() throws SQLException {
        when(connection.getAutoCommit()).thenReturn(false);
        doThrow(new SQLException("redundant configuration")).when(connection).setAutoCommit(false);

        final SqlTransaction transaction = new TransactionContext(dataSource, connection, IsolationLevel.DEFAULT, SqlTransaction.CreatedBy.JDBC_UTIL, false,
                false).enterScope(IsolationLevel.DEFAULT);

        try {
            verify(connection, never()).setAutoCommit(false);
        } finally {
            doNothing().when(connection).setAutoCommit(false);
            transaction.rollbackIfNotCommitted();
        }

        verify(connection).rollback();
        verify(connection).setAutoCommit(false);
        verify(connection).setTransactionIsolation(Connection.TRANSACTION_READ_COMMITTED);
        verify(connection, never()).close();
    }

    @Test
    public void testConstructorDoesNotReapplyCurrentIsolation() throws SQLException {
        when(connection.getAutoCommit()).thenReturn(false);
        doThrow(new SQLException("isolation cannot be changed during active work")).when(connection)
                .setTransactionIsolation(Connection.TRANSACTION_READ_COMMITTED);

        final SqlTransaction transaction = new TransactionContext(dataSource, connection, IsolationLevel.READ_COMMITTED, SqlTransaction.CreatedBy.JDBC_UTIL,
                false, false).enterScope(IsolationLevel.READ_COMMITTED);

        try {
            verify(connection, never()).setTransactionIsolation(Connection.TRANSACTION_READ_COMMITTED);
        } finally {
            doNothing().when(connection).setTransactionIsolation(Connection.TRANSACTION_READ_COMMITTED);
            transaction.rollbackIfNotCommitted();
        }

        verify(connection).rollback();
        verify(connection).setAutoCommit(false);
        verify(connection).setTransactionIsolation(Connection.TRANSACTION_READ_COMMITTED);
        verify(connection, never()).close();
    }

    @Test
    public void testNestedTransactionNoneIsRejectedWithoutChangingScope() throws SQLException {
        final SqlTransaction transaction = JdbcUtil.beginTransaction(dataSource, IsolationLevel.READ_COMMITTED);

        assertThrows(IllegalArgumentException.class, () -> transaction.context().enterScope(IsolationLevel.NONE));

        assertEquals(IsolationLevel.READ_COMMITTED, transaction.isolationLevel());
        transaction.rollbackIfNotCommitted();
        assertEquals(SqlTransaction.Status.ROLLED_BACK, transaction.status());
    }

    @Test
    public void testStatus() throws SQLException {
        final SqlTransaction transaction = JdbcUtil.beginTransaction(dataSource, IsolationLevel.READ_COMMITTED);

        assertEquals(SqlTransaction.Status.ACTIVE, transaction.status());
    }

    @Test
    public void testStatusValues() {
        assertArrayEquals(
                new SqlTransaction.Status[] { SqlTransaction.Status.ACTIVE, SqlTransaction.Status.MARKED_ROLLBACK, SqlTransaction.Status.COMMITTED,
                        SqlTransaction.Status.FAILED_COMMIT, SqlTransaction.Status.ROLLED_BACK, SqlTransaction.Status.FAILED_ROLLBACK },
                SqlTransaction.Status.values());
    }

    @Test
    public void testIsActive() throws SQLException {
        final SqlTransaction transaction = JdbcUtil.beginTransaction(dataSource, IsolationLevel.READ_COMMITTED);

        assertTrue(transaction.isActive());
    }

    @Test
    public void testCommit() throws SQLException {
        final SqlTransaction transaction = JdbcUtil.beginTransaction(dataSource, IsolationLevel.READ_COMMITTED);

        transaction.commit();

        assertEquals(SqlTransaction.Status.COMMITTED, transaction.status());
        assertFalse(transaction.isActive());
        verify(connection).commit();
        verify(connection).setAutoCommit(true);
    }

    @Test
    public void testCommitWhenOriginalAutoCommitFalse() throws SQLException {
        when(connection.getAutoCommit()).thenReturn(false);
        final SqlTransaction transaction = JdbcUtil.beginTransaction(dataSource, IsolationLevel.READ_COMMITTED);

        transaction.commit();

        assertEquals(SqlTransaction.Status.COMMITTED, transaction.status());
        verify(connection).commit();
        verify(connection).setAutoCommit(false);
    }

    @Test
    public void testCommitFailure() throws SQLException {
        final SqlTransaction transaction = JdbcUtil.beginTransaction(dataSource, IsolationLevel.READ_COMMITTED);
        doThrow(new SQLException("Commit failed")).when(connection).commit();

        assertThrows(UncheckedSQLException.class, transaction::commit);
        assertEquals(SqlTransaction.Status.ROLLED_BACK, transaction.status());
        verify(connection).rollback();
    }

    // commit() where Connection.commit() throws a *non*-SQLException: the catch only handles
    // SQLException, so commitException stays null and the finally takes the "Commit did not
    // complete transaction ... Automatically rolling back" branch (SqlTransaction L399) before
    // auto-rolling back. The original RuntimeException then propagates.
    @Test
    public void testCommit_NonSqlExceptionFromConnection_AutoRollsBack() throws SQLException {
        final SqlTransaction transaction = JdbcUtil.beginTransaction(dataSource, IsolationLevel.READ_COMMITTED);
        doThrow(new IllegalStateException("non-sql commit failure")).when(connection).commit();

        assertThrows(IllegalStateException.class, transaction::commit);
        assertEquals(SqlTransaction.Status.ROLLED_BACK, transaction.status());
        verify(connection).rollback();
    }

    // rollback() where Connection.rollback() throws a *non*-SQLException: rollbackException stays
    // null (catch handles only SQLException) so the finally takes the "Failed to roll back
    // transaction" branch (SqlTransaction L579) and leaves the status at FAILED_ROLLBACK.
    @Test
    public void testRollback_NonSqlExceptionFromConnection_HitsFailedRollbackWarn() throws SQLException {
        final SqlTransaction transaction = JdbcUtil.beginTransaction(dataSource, IsolationLevel.READ_COMMITTED);
        doThrow(new IllegalStateException("non-sql rollback failure")).when(connection).rollback();

        assertThrows(IllegalStateException.class, transaction::rollback);
        assertEquals(SqlTransaction.Status.FAILED_ROLLBACK, transaction.status());
    }

    // TX-3: addRollbackAction(Runnable) mutates the transaction's rollback-action log, which is not thread-safe and whose
    // actions run on the owner thread, so like every other mutator it is bound to the owner thread.
    @Test
    public void testAddRollbackActionRejectsNonOwnerThread() throws Exception {
        final SqlTransaction transaction = JdbcUtil.beginTransaction(dataSource, IsolationLevel.READ_COMMITTED);
        final AtomicBoolean foreignActionRan = new AtomicBoolean();
        final AtomicBoolean ownerActionRan = new AtomicBoolean();
        final AtomicReference<Throwable> failure = new AtomicReference<>();

        try {
            final Thread thread = new Thread(() -> {
                try {
                    transaction.context().addRollbackAction(() -> foreignActionRan.set(true));
                } catch (final Throwable e) {
                    failure.set(e);
                }
            }, "foreign-transaction-thread-addRollbackAction");
            thread.start();
            thread.join();

            assertTrue(failure.get() instanceof IllegalStateException, "addRollbackAction must reject the foreign thread: " + failure.get());
            assertTrue(failure.get().getMessage().contains("cannot be used from thread 'foreign-transaction-thread-addRollbackAction'"));
            assertNull(rollbackActions(transaction));

            transaction.context().addRollbackAction(() -> ownerActionRan.set(true));
        } finally {
            transaction.rollbackIfNotCommitted();
        }

        assertEquals(SqlTransaction.Status.ROLLED_BACK, transaction.status());
        assertTrue(ownerActionRan.get());
        assertFalse(foreignActionRan.get());
    }

    // TX-4 (c): a null action is ignored, without even creating the log.
    @Test
    public void testAddRollbackActionIgnoresNull() throws Exception {
        final SqlTransaction transaction = JdbcUtil.beginTransaction(dataSource, IsolationLevel.READ_COMMITTED);

        assertDoesNotThrow(() -> transaction.context().addRollbackAction(null));
        assertNull(rollbackActions(transaction));

        transaction.rollback();

        assertEquals(SqlTransaction.Status.ROLLED_BACK, transaction.status());
        verify(connection).rollback();
    }

    // TX-4 (d): the actions are an undo log - they run newest first, each at most once, when the outermost scope rolls back.
    @Test
    public void testRollbackActionsRunNewestFirstOnRollback() throws Exception {
        final SqlTransaction transaction = JdbcUtil.beginTransaction(dataSource, IsolationLevel.READ_COMMITTED);
        final List<String> order = new ArrayList<>();
        transaction.context().addRollbackAction(() -> order.add("first"));
        transaction.context().addRollbackAction(() -> order.add("second"));

        transaction.rollback();

        assertEquals(List.of("second", "first"), order);
        assertEquals(SqlTransaction.Status.ROLLED_BACK, transaction.status());
        assertNull(rollbackActions(transaction));
        verify(connection).rollback();
        verify(connection).close();
    }

    // A successful commit discards the actions: there is nothing to undo.
    @Test
    public void testRollbackActionsAreDiscardedOnCommit() throws Exception {
        final SqlTransaction transaction = JdbcUtil.beginTransaction(dataSource, IsolationLevel.READ_COMMITTED);
        final AtomicInteger runs = new AtomicInteger();
        transaction.context().addRollbackAction(runs::incrementAndGet);

        transaction.commit();

        assertEquals(0, runs.get());
        assertNull(rollbackActions(transaction));
        verify(connection).commit();
    }

    // A nested rollback marks the transaction rollback-only, which can only end rolled back: the whole log (the enclosing
    // scope's actions included) runs right away, and not again when the outermost scope completes.
    @Test
    public void testNestedRollbackRunsTheActionsOnceImmediately() throws Exception {
        final SqlTransaction outer = JdbcUtil.beginTransaction(dataSource, IsolationLevel.READ_COMMITTED);
        final AtomicInteger outerRuns = new AtomicInteger();
        outer.context().addRollbackAction(outerRuns::incrementAndGet);
        final SqlTransaction nested = JdbcUtil.beginTransaction(dataSource, IsolationLevel.READ_COMMITTED);
        assertSame(outer.context(), nested.context());
        final AtomicInteger nestedRuns = new AtomicInteger();
        nested.context().addRollbackAction(nestedRuns::incrementAndGet);

        nested.rollback();

        assertEquals(SqlTransaction.Status.MARKED_ROLLBACK, outer.status());
        assertEquals(1, nestedRuns.get());
        assertEquals(1, outerRuns.get());
        assertNull(rollbackActions(outer));
        verify(connection, never()).rollback();

        nested.rollbackIfNotCommitted(); // the cleanup paired with the nested scope's explicit rollback(): a no-op
        outer.rollbackIfNotCommitted();

        assertEquals(SqlTransaction.Status.ROLLED_BACK, outer.status());
        assertEquals(1, nestedRuns.get());
        assertEquals(1, outerRuns.get());
        verify(connection).rollback();
        verify(connection).close();
    }

    // TX-4 (a): a throwing action is logged and does not stop the remaining (older) actions or the completion of the
    // transaction. The rollback itself succeeded, so there is no primary failure to attach it to: rollback() returns normally.
    @Test
    public void testFailingRollbackActionDoesNotStopTheOthersOrTheCompletion() throws Exception {
        final SqlTransaction transaction = JdbcUtil.beginTransaction(dataSource, IsolationLevel.READ_COMMITTED);
        final List<String> order = new ArrayList<>();
        transaction.context().addRollbackAction(() -> order.add("oldest"));
        transaction.context().addRollbackAction(() -> {
            order.add("failing");
            throw new IllegalStateException("undo failed");
        });
        transaction.context().addRollbackAction(() -> order.add("newest"));

        assertDoesNotThrow(() -> transaction.rollback());

        assertEquals(List.of("newest", "failing", "oldest"), order);
        assertEquals(SqlTransaction.Status.ROLLED_BACK, transaction.status());
        assertNull(rollbackActions(transaction));
        assertFalse(JdbcUtil.isInTransaction(dataSource));
        final InOrder inOrder = inOrder(connection);
        inOrder.verify(connection).rollback();
        inOrder.verify(connection).setAutoCommit(true);
        inOrder.verify(connection).close();
    }

    // TX-4 (a)+(b): when Connection.rollback() fails, the work was still never committed, so the actions run anyway (status
    // FAILED_ROLLBACK); a failing action is attached as suppressed to the propagating rollback failure, the remaining actions
    // still run and the connection is still released (without restoring its state, which could commit the pending work).
    @Test
    public void testRollbackActionsRunAfterFailedRollbackAndTheirFailuresAreSuppressedOnIt() throws Exception {
        final SqlTransaction transaction = JdbcUtil.beginTransaction(dataSource, IsolationLevel.READ_COMMITTED);
        final List<String> order = new ArrayList<>();
        final IllegalStateException actionFailure = new IllegalStateException("undo failed");
        transaction.context().addRollbackAction(() -> order.add("oldest"));
        transaction.context().addRollbackAction(() -> {
            order.add("failing");
            throw actionFailure;
        });
        final SQLException rollbackFailure = new SQLException("rollback failed");
        doThrow(rollbackFailure).when(connection).rollback();

        final UncheckedSQLException thrown = assertThrows(UncheckedSQLException.class, transaction::rollback);

        assertSame(rollbackFailure, thrown.getCause());
        assertArrayEquals(new Throwable[] { actionFailure }, thrown.getSuppressed());
        assertEquals(List.of("failing", "oldest"), order);
        assertEquals(SqlTransaction.Status.FAILED_ROLLBACK, transaction.status());
        assertNull(rollbackActions(transaction));
        assertFalse(JdbcUtil.isInTransaction(dataSource));
        verify(connection).close();
        verify(connection, never()).setAutoCommit(true);
    }

    // TX-4 (b): a failed commit rolls the transaction back automatically, and the actions run as for any other rollback. The
    // automatic rollback itself succeeded, so a failing action is only logged: the commit failure propagates unchanged.
    @Test
    public void testRollbackActionsRunAfterTheAutomaticRollbackOfAFailedCommit() throws Exception {
        final SqlTransaction transaction = JdbcUtil.beginTransaction(dataSource, IsolationLevel.READ_COMMITTED);
        final List<String> order = new ArrayList<>();
        transaction.context().addRollbackAction(() -> order.add("oldest"));
        transaction.context().addRollbackAction(() -> {
            order.add("failing");
            throw new IllegalStateException("undo failed");
        });
        final SQLException commitFailure = new SQLException("commit failed");
        doThrow(commitFailure).when(connection).commit();

        final UncheckedSQLException thrown = assertThrows(UncheckedSQLException.class, transaction::commit);

        assertSame(commitFailure, thrown.getCause());
        assertEquals(0, thrown.getSuppressed().length);
        assertEquals(List.of("failing", "oldest"), order);
        assertEquals(SqlTransaction.Status.ROLLED_BACK, transaction.status());
        assertNull(rollbackActions(transaction));
        final InOrder inOrder = inOrder(connection);
        inOrder.verify(connection).commit();
        inOrder.verify(connection).rollback();
        inOrder.verify(connection).close();
    }

    private static RollbackActionLog rollbackActions(final SqlTransaction transaction) throws Exception {
        final Field field = TransactionContext.class.getDeclaredField("_rollbackActions");
        field.setAccessible(true);
        return (RollbackActionLog) field.get(transaction.context());
    }

    @Test
    public void testRollback() throws SQLException {
        final SqlTransaction transaction = JdbcUtil.beginTransaction(dataSource, IsolationLevel.READ_COMMITTED);

        transaction.rollback();

        assertEquals(SqlTransaction.Status.ROLLED_BACK, transaction.status());
        assertFalse(transaction.isActive());
        verify(connection).rollback();
    }

    @Test
    public void testRollbackWhenOriginalAutoCommitFalse() throws SQLException {
        when(connection.getAutoCommit()).thenReturn(false);
        final SqlTransaction transaction = JdbcUtil.beginTransaction(dataSource, IsolationLevel.READ_COMMITTED);

        transaction.rollback();

        assertEquals(SqlTransaction.Status.ROLLED_BACK, transaction.status());
        verify(connection).rollback();
        verify(connection).setAutoCommit(false);
    }

    @Test
    public void testRollbackIfNotCommitted() throws SQLException {
        final SqlTransaction transaction = JdbcUtil.beginTransaction(dataSource, IsolationLevel.READ_COMMITTED);

        transaction.rollbackIfNotCommitted();

        assertEquals(SqlTransaction.Status.ROLLED_BACK, transaction.status());
        verify(connection).rollback();
    }

    @Test
    public void testRollbackIfNotCommitted_AfterCommit() throws SQLException {
        final SqlTransaction transaction = JdbcUtil.beginTransaction(dataSource, IsolationLevel.READ_COMMITTED);

        transaction.commit();
        transaction.rollbackIfNotCommitted();

        assertEquals(SqlTransaction.Status.COMMITTED, transaction.status());
        verify(connection, times(1)).commit();
        verify(connection, never()).rollback();
    }

    @Test
    public void testRollbackIfNotCommitted_NestedMarksRollback() throws SQLException {
        final SqlTransaction transaction = JdbcUtil.beginTransaction(dataSource, IsolationLevel.DEFAULT);
        final SqlTransaction nested = transaction.context().enterScope(IsolationLevel.SERIALIZABLE);

        nested.rollbackIfNotCommitted();

        assertEquals(SqlTransaction.Status.MARKED_ROLLBACK, transaction.status());
        verify(connection, never()).rollback();
    }

    @Test
    public void testCommit_MarkedRollback_RollsBackInstead() throws SQLException {
        final SqlTransaction transaction = JdbcUtil.beginTransaction(dataSource, IsolationLevel.DEFAULT);
        final SqlTransaction nested = transaction.context().enterScope(IsolationLevel.SERIALIZABLE);
        nested.rollbackIfNotCommitted();

        assertThrows(RollbackOnlyException.class, transaction::commit);

        assertEquals(SqlTransaction.Status.ROLLED_BACK, transaction.status());
        verify(connection).rollback();
    }

    @Test
    public void testRunOutsideTransaction() throws Exception {
        final SqlTransaction transaction = JdbcUtil.beginTransaction(dataSource, IsolationLevel.READ_COMMITTED);
        final boolean[] executed = { false };

        transaction.runOutsideTransaction(() -> executed[0] = true);

        assertTrue(executed[0]);
    }

    @Test
    public void testRunOutsideTransactionRejectsNullCommand() throws Exception {
        final SqlTransaction transaction = JdbcUtil.beginTransaction(dataSource, IsolationLevel.READ_COMMITTED);

        assertThrows(IllegalArgumentException.class, () -> transaction.runOutsideTransaction(null));
        assertSame(transaction.context(), TransactionContext.getTransaction(dataSource, SqlTransaction.CreatedBy.JDBC_UTIL));
    }

    @Test
    public void testCallOutsideTransaction() throws Exception {
        final SqlTransaction transaction = JdbcUtil.beginTransaction(dataSource, IsolationLevel.READ_COMMITTED);

        final String result = transaction.callOutsideTransaction(() -> "test result");

        assertEquals("test result", result);
    }

    @Test
    public void testRunOutsideTransactionRestoresRollbackOnlyTransaction() throws Exception {
        final SqlTransaction transaction = JdbcUtil.beginTransaction(dataSource, IsolationLevel.READ_COMMITTED);
        final SqlTransaction nested = transaction.context().enterScope(IsolationLevel.READ_COMMITTED);
        nested.rollbackIfNotCommitted();
        assertEquals(SqlTransaction.Status.MARKED_ROLLBACK, transaction.status());

        transaction.runOutsideTransaction(() -> assertEquals(null, TransactionContext.getTransaction(dataSource, SqlTransaction.CreatedBy.JDBC_UTIL)));

        assertSame(transaction.context(), TransactionContext.getTransaction(dataSource, SqlTransaction.CreatedBy.JDBC_UTIL));
        transaction.rollbackIfNotCommitted();
        assertEquals(SqlTransaction.Status.ROLLED_BACK, transaction.status());
    }

    @Test
    public void testCallOutsideTransactionRejectsNullCommand() throws Exception {
        final SqlTransaction transaction = JdbcUtil.beginTransaction(dataSource, IsolationLevel.READ_COMMITTED);

        assertThrows(IllegalArgumentException.class, () -> transaction.callOutsideTransaction(null));
        assertSame(transaction.context(), TransactionContext.getTransaction(dataSource, SqlTransaction.CreatedBy.JDBC_UTIL));
    }

    @Test
    public void testRunOutsideTransactionAfterCommitDoesNotRestoreCompletedTransaction() throws Exception {
        final Connection connection2 = Mockito.mock(Connection.class);
        when(connection2.getAutoCommit()).thenReturn(true);
        when(connection2.getTransactionIsolation()).thenReturn(Connection.TRANSACTION_READ_COMMITTED);
        when(dataSource.getConnection()).thenReturn(connection, connection2);

        final SqlTransaction transaction = JdbcUtil.beginTransaction(dataSource, IsolationLevel.READ_COMMITTED);
        transaction.commit();

        transaction.runOutsideTransaction(() -> {
            // no-op
        });

        final SqlTransaction nextTransaction = assertDoesNotThrow(() -> JdbcUtil.beginTransaction(dataSource, IsolationLevel.READ_COMMITTED));

        assertSame(connection2, nextTransaction.connection());
        verify(dataSource, times(2)).getConnection();

        nextTransaction.rollbackIfNotCommitted();
    }

    @Test
    public void testCallOutsideTransactionAfterRollbackDoesNotRestoreCompletedTransaction() throws Exception {
        final Connection connection2 = Mockito.mock(Connection.class);
        when(connection2.getAutoCommit()).thenReturn(true);
        when(connection2.getTransactionIsolation()).thenReturn(Connection.TRANSACTION_READ_COMMITTED);
        when(dataSource.getConnection()).thenReturn(connection, connection2);

        final SqlTransaction transaction = JdbcUtil.beginTransaction(dataSource, IsolationLevel.READ_COMMITTED);
        transaction.rollback();

        assertEquals("outside", transaction.callOutsideTransaction(() -> "outside"));

        final SqlTransaction nextTransaction = assertDoesNotThrow(() -> JdbcUtil.beginTransaction(dataSource, IsolationLevel.READ_COMMITTED));

        assertSame(connection2, nextTransaction.connection());
        verify(dataSource, times(2)).getConnection();

        nextTransaction.rollbackIfNotCommitted();
    }

    @Test
    public void testClose() throws SQLException {
        final SqlTransaction transaction = JdbcUtil.beginTransaction(dataSource, IsolationLevel.READ_COMMITTED);

        transaction.close();

        assertEquals(SqlTransaction.Status.ROLLED_BACK, transaction.status());
        verify(connection).rollback();
    }

    @Test
    public void testHashCode() throws SQLException {
        final SqlTransaction transaction = JdbcUtil.beginTransaction(dataSource, IsolationLevel.READ_COMMITTED);

        // Scopes are distinct handles: identity-based equality and hash code.
        assertEquals(System.identityHashCode(transaction), transaction.hashCode());
    }

    @Test
    public void testEquals() throws SQLException {
        final SqlTransaction transaction1 = JdbcUtil.beginTransaction(dataSource, IsolationLevel.READ_COMMITTED);
        final SqlTransaction transaction2 = JdbcUtil.beginTransaction(dataSource, IsolationLevel.READ_COMMITTED);

        // A nested scope is a different handle of the same transaction.
        assertNotEquals(transaction1, transaction2);
        assertEquals(transaction1.id(), transaction2.id());
        assertSame(transaction1.context(), transaction2.context());
        assertEquals(transaction1, transaction1);
        assertNotEquals(transaction1, null);
        assertNotEquals(transaction1, "not a transaction");
    }

    @Test
    public void testToString() throws SQLException {
        final SqlTransaction transaction = JdbcUtil.beginTransaction(dataSource, IsolationLevel.READ_COMMITTED);

        final String toString = transaction.toString();
        assertNotNull(toString);
        assertTrue(toString.contains("SqlTransaction"));
        assertTrue(toString.contains("id="));
    }

    @Test
    public void testEnterNestedScope() throws SQLException {
        final SqlTransaction transaction = JdbcUtil.beginTransaction(dataSource, IsolationLevel.READ_COMMITTED);

        final SqlTransaction nested = transaction.context().enterScope(IsolationLevel.SERIALIZABLE);

        assertNotSame(transaction, nested);
        assertEquals(2, transaction.context().depth());
        assertEquals(IsolationLevel.SERIALIZABLE, nested.isolationLevel());
        assertEquals(IsolationLevel.READ_COMMITTED, transaction.isolationLevel());
        assertEquals(IsolationLevel.SERIALIZABLE, transaction.context().isolationLevel());
        verify(connection).setTransactionIsolation(Connection.TRANSACTION_SERIALIZABLE);
    }

    @Test
    public void testEnterScope_AfterCommit() throws SQLException {
        final SqlTransaction transaction = JdbcUtil.beginTransaction(dataSource, IsolationLevel.READ_COMMITTED);
        transaction.commit();

        assertThrows(IllegalStateException.class, () -> transaction.context().enterScope(IsolationLevel.READ_COMMITTED));
    }

    @Test
    public void testNestedCommit_RestoresOriginalIsolationForDefault() throws SQLException {
        final SqlTransaction transaction = JdbcUtil.beginTransaction(dataSource, IsolationLevel.DEFAULT);
        final SqlTransaction nested = transaction.context().enterScope(IsolationLevel.SERIALIZABLE);

        nested.commit();

        assertEquals(1, transaction.context().depth());
        assertEquals(IsolationLevel.DEFAULT, transaction.context().isolationLevel());
        verify(connection).setTransactionIsolation(Connection.TRANSACTION_SERIALIZABLE);
        verify(connection).setTransactionIsolation(Connection.TRANSACTION_READ_COMMITTED);
    }

    // A setTransactionIsolation failure while leaving a nested scope must be retry-safe: the scope stays open and current
    // (depth and isolation unchanged), so a later exit attempt can still succeed.
    @Test
    public void testNestedExitFailureIsRetrySafe() throws SQLException {
        final SqlTransaction transaction = JdbcUtil.beginTransaction(dataSource, IsolationLevel.READ_COMMITTED);
        final SqlTransaction nested = transaction.context().enterScope(IsolationLevel.SERIALIZABLE);
        assertEquals(IsolationLevel.SERIALIZABLE, transaction.context().isolationLevel());

        // Fail only the first restore back to READ_COMMITTED on the nested exit; the retry succeeds.
        doThrow(new SQLException("restore failed")).doNothing().when(connection).setTransactionIsolation(Connection.TRANSACTION_READ_COMMITTED);

        assertThrows(UncheckedSQLException.class, nested::commit);

        // The failed exit left the nested scope open and current.
        assertEquals(IsolationLevel.SERIALIZABLE, transaction.context().isolationLevel());
        assertEquals(2, transaction.context().depth());

        nested.commit();
        assertEquals(1, transaction.context().depth());
        assertEquals(IsolationLevel.READ_COMMITTED, transaction.context().isolationLevel());
    }

    // PostgreSQL rejects setTransactionIsolation once the transaction has started, so restoring the enclosing scope's level on a
    // nested exit fails every time. commit() stays retry-safe, but the paired cleanup re-armed the scope too, so the scope never
    // exited: the outermost commit()/rollbackIfNotCommitted() only ever retried the nested exit, and the transaction, its registry
    // entry and its connection leaked. The cleanup now exits anyway and marks the transaction rollback-only; the outermost
    // completion rolls back, restores the original isolation level and releases the connection.
    @Test
    public void testNestedCleanupExitsAndMarksRollbackOnlyWhenIsolationRestoreKeepsFailing() throws SQLException {
        final SqlTransaction outer = JdbcUtil.beginTransaction(dataSource, IsolationLevel.READ_COMMITTED);
        final SqlTransaction nested = JdbcUtil.beginTransaction(dataSource, IsolationLevel.SERIALIZABLE);
        assertNotSame(outer, nested);
        assertSame(outer.context(), nested.context());

        final AtomicBoolean rolledBack = new AtomicBoolean();
        Mockito.doAnswer(invocation -> {
            rolledBack.set(true);
            return null;
        }).when(connection).rollback();
        Mockito.doAnswer(invocation -> {
            if (!rolledBack.get()) {
                throw new SQLException("cannot change transaction isolation level in the middle of a transaction");
            }
            return null;
        }).when(connection).setTransactionIsolation(Connection.TRANSACTION_READ_COMMITTED);

        try {
            assertThrows(UncheckedSQLException.class, nested::commit);
        } finally {
            assertDoesNotThrow(nested::rollbackIfNotCommitted);
        }

        assertEquals(SqlTransaction.Status.MARKED_ROLLBACK, outer.status());
        assertTrue(JdbcUtil.isInTransaction(dataSource));

        try {
            assertThrows(RollbackOnlyException.class, outer::commit);
        } finally {
            outer.rollbackIfNotCommitted();
        }

        assertEquals(SqlTransaction.Status.ROLLED_BACK, outer.status());
        assertFalse(JdbcUtil.isInTransaction(dataSource));
        final org.mockito.InOrder inOrder = Mockito.inOrder(connection);
        inOrder.verify(connection).rollback();
        inOrder.verify(connection).setTransactionIsolation(Connection.TRANSACTION_READ_COMMITTED);
        inOrder.verify(connection).close();
        verify(connection, never()).commit();
    }

    // TX-2: the forced (cleanup) exit of a nested scope must not reduce an unchecked driver failure to a WARN. An SQLException
    // from Connection.setTransactionIsolation is a cleanup failure to log (previous test), but a RuntimeException or an Error
    // (a LinkageError, an OutOfMemoryError) propagates - as from resetAndCloseConnection() - after the scope has been exited,
    // the transaction marked rollback-only and the nested scope's rollback actions run, so that the outermost completion
    // still rolls back and releases the connection. Before the fix both were discarded (rollbackIfNotCommitted() returned).
    @Test
    public void testNestedCleanupPropagatesErrorAfterExitingTheScopeAndMarkingRollbackOnly() throws SQLException {
        assertForcedNestedExitPropagatesUncheckedFailure(new LinkageError("driver class unloaded"));
    }

    @Test
    public void testNestedCleanupPropagatesRuntimeExceptionAfterExitingTheScopeAndMarkingRollbackOnly() throws SQLException {
        assertForcedNestedExitPropagatesUncheckedFailure(new IllegalStateException("connection state is unusable"));
    }

    private void assertForcedNestedExitPropagatesUncheckedFailure(final Throwable restoreFailure) throws SQLException {
        final SqlTransaction outer = JdbcUtil.beginTransaction(dataSource, IsolationLevel.READ_COMMITTED);
        final SqlTransaction nested = JdbcUtil.beginTransaction(dataSource, IsolationLevel.SERIALIZABLE);
        assertNotSame(outer, nested);
        assertSame(outer.context(), nested.context());

        final AtomicInteger nestedActionRuns = new AtomicInteger();
        nested.context().addRollbackAction(nestedActionRuns::incrementAndGet);

        final AtomicBoolean rolledBack = new AtomicBoolean();
        Mockito.doAnswer(invocation -> {
            rolledBack.set(true);
            return null;
        }).when(connection).rollback();
        Mockito.doAnswer(invocation -> {
            if (!rolledBack.get()) {
                throw restoreFailure;
            }
            return null;
        }).when(connection).setTransactionIsolation(Connection.TRANSACTION_READ_COMMITTED);

        try {
            // The retryable exit keeps the nested scope current and rethrows the failure.
            assertSame(restoreFailure, assertThrows(restoreFailure.getClass(), nested::commit));
            assertEquals(IsolationLevel.SERIALIZABLE, nested.isolationLevel());
            assertEquals(0, nestedActionRuns.get());
        } finally {
            // The paired cleanup exits the scope anyway, marks the transaction rollback-only, runs the rollback actions and
            // then propagates the failure instead of discarding it.
            assertSame(restoreFailure, assertThrows(restoreFailure.getClass(), nested::rollbackIfNotCommitted));
        }

        assertEquals(SqlTransaction.Status.MARKED_ROLLBACK, outer.status());
        assertEquals(IsolationLevel.READ_COMMITTED, outer.isolationLevel());
        assertEquals(1, nestedActionRuns.get());
        assertTrue(JdbcUtil.isInTransaction(dataSource));

        try {
            assertThrows(RollbackOnlyException.class, outer::commit);
        } finally {
            outer.rollbackIfNotCommitted();
        }

        assertEquals(SqlTransaction.Status.ROLLED_BACK, outer.status());
        assertEquals(1, nestedActionRuns.get());
        assertFalse(JdbcUtil.isInTransaction(dataSource));
        final InOrder inOrder = inOrder(connection);
        inOrder.verify(connection).rollback();
        inOrder.verify(connection).setTransactionIsolation(Connection.TRANSACTION_READ_COMMITTED);
        inOrder.verify(connection).close();
        verify(connection, never()).commit();
    }

    @Test
    public void testFailedNestedExitRestoresPhysicalIsolationBeforeRetry() throws SQLException {
        final Throwable[] failures = { new SQLException("isolation changed before SQL failure"),
                new IllegalStateException("isolation changed before unchecked failure"), new AssertionError("isolation changed before error") };

        for (final Throwable failure : failures) {
            final Connection mutableConnection = Mockito.mock(Connection.class);
            final AtomicInteger physicalIsolation = new AtomicInteger(Connection.TRANSACTION_READ_COMMITTED);
            when(mutableConnection.getAutoCommit()).thenReturn(true);
            when(mutableConnection.getTransactionIsolation()).thenAnswer(invocation -> physicalIsolation.get());
            Mockito.doAnswer(invocation -> {
                physicalIsolation.set(invocation.getArgument(0));
                return null;
            }).when(mutableConnection).setTransactionIsolation(ArgumentMatchers.anyInt());

            final SqlTransaction transaction = new TransactionContext(null, mutableConnection, IsolationLevel.DEFAULT, SqlTransaction.CreatedBy.JDBC_UTIL,
                    false, false).enterScope(IsolationLevel.DEFAULT);
            final SqlTransaction nested = transaction.context().enterScope(IsolationLevel.SERIALIZABLE);

            final AtomicBoolean failNextRestore = new AtomicBoolean(true);
            Mockito.doAnswer(invocation -> {
                final int requestedIsolation = invocation.getArgument(0);
                physicalIsolation.set(requestedIsolation);
                if (requestedIsolation == Connection.TRANSACTION_READ_COMMITTED && failNextRestore.getAndSet(false)) {
                    throw failure;
                }
                return null;
            }).when(mutableConnection).setTransactionIsolation(ArgumentMatchers.anyInt());

            try {
                final Throwable thrown = assertThrows(Throwable.class, nested::commit);
                assertSame(failure, thrown instanceof UncheckedSQLException ? thrown.getCause() : thrown);
                assertEquals(IsolationLevel.SERIALIZABLE, transaction.context().isolationLevel());
                assertEquals(Connection.TRANSACTION_SERIALIZABLE, mutableConnection.getTransactionIsolation());
                verify(mutableConnection, never()).commit();

                nested.commit();
                nested.rollbackIfNotCommitted();
                assertEquals(IsolationLevel.DEFAULT, transaction.context().isolationLevel());
                assertEquals(Connection.TRANSACTION_READ_COMMITTED, mutableConnection.getTransactionIsolation());
                transaction.commit();
                verify(mutableConnection).commit();
            } finally {
                transaction.rollbackIfNotCommitted();
            }
        }
    }

    @Test
    public void testFailedNestedExitPreservesIsolationRecoveryFailure() throws SQLException {
        final SqlTransaction transaction = JdbcUtil.beginTransaction(dataSource, IsolationLevel.READ_COMMITTED);
        final SqlTransaction nested = transaction.context().enterScope(IsolationLevel.SERIALIZABLE);
        final SQLException failure = new SQLException("cannot restore outer isolation");
        final IllegalStateException recoveryFailure = new IllegalStateException("cannot recover nested isolation");
        doThrow(failure).when(connection).setTransactionIsolation(Connection.TRANSACTION_READ_COMMITTED);
        doThrow(recoveryFailure).when(connection).setTransactionIsolation(Connection.TRANSACTION_SERIALIZABLE);

        try {
            final UncheckedSQLException thrown = assertThrows(UncheckedSQLException.class, nested::commit);
            assertSame(failure, thrown.getCause());
            assertArrayEquals(new Throwable[] { recoveryFailure }, thrown.getSuppressed());
            assertEquals(IsolationLevel.SERIALIZABLE, transaction.context().isolationLevel());
        } finally {
            doNothing().when(connection).setTransactionIsolation(ArgumentMatchers.anyInt());
            nested.rollbackIfNotCommitted();
            transaction.rollbackIfNotCommitted();
        }
    }

    @Test
    public void testGetTransactionId() {
        final String id = TransactionContext.getTransactionId(dataSource, SqlTransaction.CreatedBy.JDBC_UTIL);
        assertNotNull(id);
        assertTrue(id.contains(String.valueOf(System.identityHashCode(dataSource))));
        assertTrue(id.contains(String.valueOf(Thread.currentThread().getId())));
    }

    @Test
    public void testTransactionRegistryUsesCollisionSafeCompositeKey() throws Exception {
        clearThreadTransactionMap();
        final SqlTransaction transaction = JdbcUtil.beginTransaction(dataSource, IsolationLevel.READ_COMMITTED);

        try {
            final Field mapField = TransactionContext.class.getDeclaredField("threadTransactionMap");
            mapField.setAccessible(true);
            final java.util.Map<?, ?> registry = (java.util.Map<?, ?>) mapField.get(null);
            final Object key = registry.entrySet()
                    .stream()
                    .filter(entry -> entry.getValue() == transaction.context())
                    .map(java.util.Map.Entry::getKey)
                    .findFirst()
                    .orElse(null);

            assertNotNull(key);
            assertFalse(key instanceof String, "Transaction lookup must not depend on a concatenated identity-hash string");
            assertSame(transaction.context(), TransactionContext.getTransaction(dataSource, SqlTransaction.CreatedBy.JDBC_UTIL));
        } finally {
            transaction.rollbackIfNotCommitted();
        }
    }

    // commit() may be called only once per scope, as in Spring: a second commit() of a committed scope throws (it was logged
    // and ignored), and the database commit runs once.
    @Test
    public void testCommit_WhenAlreadyCommitted_Throws() throws SQLException {
        final SqlTransaction transaction = JdbcUtil.beginTransaction(dataSource, IsolationLevel.READ_COMMITTED);
        transaction.commit();

        final IllegalStateException thrown = assertThrows(IllegalStateException.class, transaction::commit);
        assertTrue(thrown.getMessage().contains("has already been committed (status: COMMITTED)"), thrown.getMessage());
        assertFalse(thrown instanceof RollbackOnlyException);
        assertEquals(SqlTransaction.Status.COMMITTED, transaction.status());
        verify(connection, times(1)).commit();
    }

    // A repeated deprecated rollback() of a completed scope throws, as in Spring and as commit() (user decision; it was logged and
    // ignored): the database rollback runs once, and the cleanup calls stay no-ops.
    @SuppressWarnings("deprecation")
    @Test
    public void testRollback_WhenAlreadyRolledBack_Throws() throws SQLException {
        final SqlTransaction transaction = JdbcUtil.beginTransaction(dataSource, IsolationLevel.READ_COMMITTED);
        transaction.rollback();

        final IllegalStateException thrown = assertThrows(IllegalStateException.class, transaction::rollback);
        assertTrue(thrown.getMessage().contains("has already completed (status: ROLLED_BACK)"), thrown.getMessage());
        assertDoesNotThrow(transaction::rollbackIfNotCommitted);
        assertDoesNotThrow(transaction::close);
        verify(connection, times(1)).rollback();
    }

    // The same for a nested scope, also one an enclosing scope exited: the transaction is left as it was.
    @SuppressWarnings("deprecation")
    @Test
    public void testRollback_OfACompletedNestedScope_Throws() throws SQLException {
        final SqlTransaction outer = JdbcUtil.beginTransaction(dataSource, IsolationLevel.READ_COMMITTED);

        try {
            final SqlTransaction committed = JdbcUtil.beginTransaction(dataSource);
            committed.commit();
            assertThrows(IllegalStateException.class, committed::rollback);
            assertEquals(SqlTransaction.Status.ACTIVE, outer.status(), "the transaction is not marked rollback-only");

            final SqlTransaction middle = JdbcUtil.beginTransaction(dataSource);
            final SqlTransaction inner = JdbcUtil.beginTransaction(dataSource);
            middle.rollbackIfNotCommitted(); // exits inner, which was never completed
            assertThrows(IllegalStateException.class, inner::rollback);
            assertDoesNotThrow(inner::rollbackIfNotCommitted);
        } finally {
            outer.rollbackIfNotCommitted();
        }
    }

    // runOutsideTransaction: exception thrown inside cmd should propagate (lines 694-696)
    @Test
    public void testRunOutsideTransaction_ExceptionPropagates() throws Exception {
        final SqlTransaction transaction = JdbcUtil.beginTransaction(dataSource, IsolationLevel.READ_COMMITTED);
        try {
            assertThrows(RuntimeException.class, () -> transaction.runOutsideTransaction(() -> {
                throw new RuntimeException("inner error");
            }));
        } finally {
            transaction.rollbackIfNotCommitted();
        }
    }

    // callOutsideTransaction: exception thrown inside cmd should propagate (lines 757-759)
    @Test
    public void testCallOutsideTransaction_ExceptionPropagates() throws Exception {
        final SqlTransaction transaction = JdbcUtil.beginTransaction(dataSource, IsolationLevel.READ_COMMITTED);
        try {
            assertThrows(RuntimeException.class, () -> transaction.callOutsideTransaction(() -> {
                throw new RuntimeException("callable error");
            }));
        } finally {
            transaction.rollbackIfNotCommitted();
        }
    }

    @Test
    public void testBeginTransactionReusesWhenThreadNameChanges() throws SQLException {
        final String originalThreadName = Thread.currentThread().getName();
        SqlTransaction transaction1 = null;
        SqlTransaction transaction2 = null;

        try {
            transaction1 = JdbcUtil.beginTransaction(dataSource, IsolationLevel.READ_COMMITTED);
            Thread.currentThread().setName(originalThreadName + "_renamed");
            transaction2 = JdbcUtil.beginTransaction(dataSource, IsolationLevel.READ_COMMITTED);

            assertSame(transaction1.context(), transaction2.context());

            transaction2.commit();
            transaction1.commit();

            verify(dataSource, times(1)).getConnection();
            verify(connection, times(1)).commit();
        } finally {
            Thread.currentThread().setName(originalThreadName);

            if (transaction2 != null) {
                transaction2.rollbackIfNotCommitted();
            }

            if (transaction1 != null && transaction1 != transaction2) {
                transaction1.rollbackIfNotCommitted();
            }
        }
    }

    // rollback() of a nested scope marks the transaction MARKED_ROLLBACK without executing the actual rollback
    @Test
    public void testRollback_WhenNested_MarksForRollback() throws SQLException {
        final SqlTransaction transaction = JdbcUtil.beginTransaction(dataSource, IsolationLevel.READ_COMMITTED);
        final SqlTransaction nested = transaction.context().enterScope(IsolationLevel.READ_COMMITTED);

        // nested rollback: marks MARKED_ROLLBACK, does NOT actually roll back
        nested.rollback();

        assertEquals(SqlTransaction.Status.MARKED_ROLLBACK, transaction.status());
        verify(connection, never()).rollback();

        // outermost rollback: actually rolls back
        transaction.rollback();
        assertEquals(SqlTransaction.Status.ROLLED_BACK, transaction.status());
        verify(connection, times(1)).rollback();
    }

    // rollbackIfNotCommitted() after a direct rollbackIfNotCommitted() returns early on second call (L436)
    @Test
    public void testRollbackIfNotCommitted_WhenAlreadyRolledBackDirectly_DoesNothing() throws SQLException {
        final SqlTransaction transaction = JdbcUtil.beginTransaction(dataSource, IsolationLevel.READ_COMMITTED);

        transaction.rollbackIfNotCommitted(); // actual rollback
        transaction.rollbackIfNotCommitted(); // a no-op: the scope has completed

        assertEquals(SqlTransaction.Status.ROLLED_BACK, transaction.status());
        verify(connection, times(1)).rollback();
    }

    // rollback() when connection.rollback() throws SQLException -> UncheckedSQLException, status=FAILED_ROLLBACK
    @Test
    public void testRollback_WhenRollbackFails_ThrowsUncheckedSQLException() throws SQLException {
        final SqlTransaction transaction = JdbcUtil.beginTransaction(dataSource, IsolationLevel.READ_COMMITTED);
        doThrow(new SQLException("Rollback failed")).when(connection).rollback();

        assertThrows(UncheckedSQLException.class, transaction::rollback);
        assertEquals(SqlTransaction.Status.FAILED_ROLLBACK, transaction.status());
    }

    // resetAndCloseConnection() swallows SQLException from setAutoCommit (L507-508)
    @Test
    public void testRollback_WhenResetConnectionFails_SwallowsException() throws SQLException {
        final SqlTransaction transaction = JdbcUtil.beginTransaction(dataSource, IsolationLevel.READ_COMMITTED);
        doThrow(new SQLException("setAutoCommit failed")).when(connection).setAutoCommit(true);

        // rollback should succeed even if resetting connection fails
        assertDoesNotThrow((org.junit.jupiter.api.function.Executable) transaction::rollback);
        assertEquals(SqlTransaction.Status.ROLLED_BACK, transaction.status());
        verify(connection).rollback();
    }

    // commit() fails, then rollback also fails: the commit failure remains primary and the rollback failure is suppressed.
    @Test
    public void testCommit_WhenRollbackAlsoFails_RollbackExceptionSuppressed() throws SQLException {
        final SqlTransaction transaction = JdbcUtil.beginTransaction(dataSource, IsolationLevel.READ_COMMITTED);
        doThrow(new SQLException("Commit failed")).when(connection).commit();
        doThrow(new SQLException("Rollback also failed")).when(connection).rollback();

        final UncheckedSQLException thrown = assertThrows(UncheckedSQLException.class, transaction::commit);
        assertEquals(1, thrown.getSuppressed().length);
        assertTrue(thrown.getSuppressed()[0] instanceof UncheckedSQLException);
        assertEquals("Rollback also failed", thrown.getSuppressed()[0].getCause().getMessage());
        assertEquals(SqlTransaction.Status.FAILED_ROLLBACK, transaction.status());
        verify(connection).rollback();
    }

    @Test
    public void testCommit_WhenRollbackThrowsError_CommitFailureRemainsPrimary() throws SQLException {
        final SqlTransaction transaction = JdbcUtil.beginTransaction(dataSource, IsolationLevel.READ_COMMITTED);
        doThrow(new SQLException("Commit failed")).when(connection).commit();
        doThrow(new AssertionError("Rollback error")).when(connection).rollback();

        final UncheckedSQLException thrown = assertThrows(UncheckedSQLException.class, transaction::commit);

        assertEquals("Commit failed", thrown.getCause().getMessage());
        assertEquals(1, thrown.getSuppressed().length);
        assertTrue(thrown.getSuppressed()[0] instanceof AssertionError);
        assertEquals("Rollback error", thrown.getSuppressed()[0].getMessage());
        assertEquals(SqlTransaction.Status.FAILED_ROLLBACK, transaction.status());
    }

    @Test
    public void testCommitUncheckedFailureRemainsPrimaryWhenRollbackAlsoFails() throws SQLException {
        final SqlTransaction transaction = JdbcUtil.beginTransaction(dataSource, IsolationLevel.READ_COMMITTED);
        final AssertionError commitFailure = new AssertionError("unchecked commit failure");
        final IllegalStateException rollbackFailure = new IllegalStateException("unchecked rollback failure");
        doThrow(commitFailure).when(connection).commit();
        doThrow(rollbackFailure).when(connection).rollback();

        final AssertionError thrown = assertThrows(AssertionError.class, transaction::commit);

        assertSame(commitFailure, thrown);
        assertArrayEquals(new Throwable[] { rollbackFailure }, thrown.getSuppressed());
        assertEquals(SqlTransaction.Status.FAILED_ROLLBACK, transaction.status());
    }

    // rollback() after a commit throws: it returned normally, as if it had undone the committed work.
    @SuppressWarnings("deprecation")
    @Test
    public void testRollback_WhenAlreadyCommitted_Throws() throws SQLException {
        final SqlTransaction transaction = JdbcUtil.beginTransaction(dataSource, IsolationLevel.READ_COMMITTED);
        transaction.commit();

        assertTrue(assertThrows(IllegalStateException.class, transaction::rollback).getMessage().contains("status: COMMITTED"));
        assertEquals(SqlTransaction.Status.COMMITTED, transaction.status());
        verify(connection, never()).rollback();
    }

    // entering a nested scope throws UncheckedSQLException when setTransactionIsolation fails
    @Test
    public void testEnterScope_WhenSetIsolationFails_ThrowsUncheckedSQLException() throws SQLException {
        final SqlTransaction transaction = JdbcUtil.beginTransaction(dataSource, IsolationLevel.READ_COMMITTED);
        doThrow(new SQLException("setIsolation failed")).when(connection).setTransactionIsolation(org.mockito.ArgumentMatchers.anyInt());
        assertThrows(UncheckedSQLException.class, () -> transaction.context().enterScope(IsolationLevel.SERIALIZABLE));
        transaction.rollbackIfNotCommitted();
    }

    // Round 16 review: the deprecated rollback() of a nested scope whose exit could not restore the enclosing isolation level
    // threw and left the scope open, the transaction ACTIVE after an explicit rollback. It exits the scope as
    // rollbackIfNotCommitted() does: the SQLException is logged, and the transaction is marked rollback-only.
    @SuppressWarnings("deprecation")
    @Test
    public void testNestedRollback_WhenRestoreIsolationFails_ExitsTheScopeAndMarksTheTransactionRollbackOnly() throws SQLException {
        final SqlTransaction transaction = JdbcUtil.beginTransaction(dataSource, IsolationLevel.READ_COMMITTED);
        // open a nested scope; this works since setTransactionIsolation is not yet throwing
        final SqlTransaction nested = transaction.context().enterScope(IsolationLevel.SERIALIZABLE);
        // now make setTransactionIsolation throw on restore
        doThrow(new SQLException("restore failed")).when(connection).setTransactionIsolation(org.mockito.ArgumentMatchers.anyInt());

        nested.rollback();

        assertEquals(SqlTransaction.Status.MARKED_ROLLBACK, transaction.status());
        assertEquals(1, transaction.context().depth(), "the nested scope was exited");
        assertThrows(IllegalStateException.class, nested::commit, "the nested scope has completed");
        assertThrows(RollbackOnlyException.class, transaction::commit);
        verify(connection).rollback();
    }

    // As above, with an unchecked failure of the restore: it propagates once the scope has been exited.
    @SuppressWarnings("deprecation")
    @Test
    public void testNestedRollback_WhenRestoreIsolationFailsUnchecked_ThrowsOnceTheScopeHasBeenExited() throws SQLException {
        final SqlTransaction transaction = JdbcUtil.beginTransaction(dataSource, IsolationLevel.READ_COMMITTED);
        final SqlTransaction nested = transaction.context().enterScope(IsolationLevel.SERIALIZABLE);
        final IllegalStateException restoreFailure = new IllegalStateException("restore failed");
        doThrow(restoreFailure).when(connection).setTransactionIsolation(org.mockito.ArgumentMatchers.anyInt());

        assertSame(restoreFailure, assertThrows(IllegalStateException.class, nested::rollback));

        assertEquals(SqlTransaction.Status.MARKED_ROLLBACK, transaction.status());
        assertEquals(1, transaction.context().depth());
        doNothing().when(connection).setTransactionIsolation(org.mockito.ArgumentMatchers.anyInt()); // the outermost cleanup restores it
        transaction.rollbackIfNotCommitted();
        assertEquals(SqlTransaction.Status.ROLLED_BACK, transaction.status());
    }

    // Round 16 review: callOutsideTransaction noticed a transaction its command left open only while this transaction was still
    // open: once the command had completed this one too, the transaction it left open stayed registered on the (pooled) thread,
    // where later work on the data source joined it.
    @Test
    public void testCallOutsideTransactionWhoseCmdCompletesTheTransactionAndLeavesAnotherOpenThrows() throws SQLException {
        final SqlTransaction outerTx = JdbcUtil.beginTransaction(dataSource, IsolationLevel.READ_COMMITTED);
        final AtomicReference<SqlTransaction> opened = new AtomicReference<>();

        try {
            final IllegalStateException thrown = assertThrows(IllegalStateException.class, () -> outerTx.runOutsideTransaction(() -> {
                opened.set(JdbcUtil.beginTransaction(dataSource, IsolationLevel.READ_COMMITTED));
                outerTx.commit();
            }));
            assertTrue(thrown.getMessage().contains("Another transaction is opened but not closed"), thrown.getMessage());
            assertEquals(SqlTransaction.Status.COMMITTED, outerTx.status());
        } finally {
            if (opened.get() != null) {
                opened.get().rollbackIfNotCommitted();
            }

            outerTx.rollbackIfNotCommitted();
        }

        assertNull(TransactionContext.getTransaction(dataSource, SqlTransaction.CreatedBy.JDBC_UTIL));
    }

    // runOutsideTransaction(): another transaction opened during cmd causes IllegalStateException (L699-705)
    @Test
    public void testRunOutsideTransaction_WhenAnotherTransactionOpenedInsideCmd_ThrowsISE() throws SQLException {
        final AtomicReference<SqlTransaction> opened = new AtomicReference<>();
        final SqlTransaction outerTx = JdbcUtil.beginTransaction(dataSource, IsolationLevel.READ_COMMITTED);

        try {
            // cmd starts another transaction for the same registry slot → conflict in finally
            assertThrows(IllegalStateException.class, () -> outerTx.runOutsideTransaction(() -> {
                opened.set(JdbcUtil.beginTransaction(dataSource, IsolationLevel.READ_COMMITTED));
            }));
        } finally {
            // A no-op: the transaction left open has been rolled back (see testRunOutsideTransactionRollsBackTheTransactionItsCmdLeftOpen...).
            final SqlTransaction remaining = opened.get();
            if (remaining != null) {
                remaining.rollbackIfNotCommitted();
            }
        }
    }

    // callOutsideTransaction(): another transaction opened during cmd causes IllegalStateException (L762-768)
    @Test
    public void testCallOutsideTransaction_WhenAnotherTransactionOpenedInsideCmd_ThrowsISE() throws SQLException {
        final AtomicReference<SqlTransaction> opened = new AtomicReference<>();
        final SqlTransaction outerTx = JdbcUtil.beginTransaction(dataSource, IsolationLevel.READ_COMMITTED);

        try {
            assertThrows(IllegalStateException.class, () -> outerTx.callOutsideTransaction(() -> {
                opened.set(JdbcUtil.beginTransaction(dataSource, IsolationLevel.READ_COMMITTED));
                return null;
            }));
        } finally {
            final SqlTransaction remaining = opened.get();
            if (remaining != null) {
                remaining.rollbackIfNotCommitted();
            }
        }
    }

    // Round 17 review: a transaction that the command of runOutsideTransaction left open was only reported: it stayed registered
    // in place of the transaction the command ran outside, so the work on the data source meant for that one joined it (and it
    // held its connection on the thread). It is rolled back now (its nested scopes exited first), and the transaction the
    // command ran outside is registered again.
    @Test
    public void testRunOutsideTransactionRollsBackTheTransactionItsCmdLeftOpenAndRegistersThisOneAgain() throws Exception {
        clearThreadTransactionMap();

        final Connection connection2 = Mockito.mock(Connection.class);
        when(connection2.getAutoCommit()).thenReturn(true);
        when(connection2.getTransactionIsolation()).thenReturn(Connection.TRANSACTION_READ_COMMITTED);
        when(dataSource.getConnection()).thenReturn(connection, connection2);

        final SqlTransaction outerTx = JdbcUtil.beginTransaction(dataSource, IsolationLevel.READ_COMMITTED);
        final AtomicReference<SqlTransaction> opened = new AtomicReference<>();
        final AtomicReference<SqlTransaction> openedNested = new AtomicReference<>();

        try {
            final IllegalStateException thrown = assertThrows(IllegalStateException.class, () -> outerTx.runOutsideTransaction(() -> {
                opened.set(JdbcUtil.beginTransaction(dataSource, IsolationLevel.READ_COMMITTED));
                openedNested.set(JdbcUtil.beginTransaction(dataSource, IsolationLevel.READ_COMMITTED));
            }));
            assertTrue(thrown.getMessage().contains("it has been rolled back"), thrown.getMessage());

            assertNotSame(outerTx.context(), opened.get().context());
            assertEquals(SqlTransaction.Status.ROLLED_BACK, opened.get().status());
            assertTrue(openedNested.get().isCompleted(), "its nested scope was exited first");
            verify(connection2).rollback();
            verify(connection, never()).rollback();

            assertEquals(SqlTransaction.Status.ACTIVE, outerTx.status());
            assertSame(outerTx.context(), TransactionContext.getTransaction(dataSource, SqlTransaction.CreatedBy.JDBC_UTIL));

            final SqlTransaction joined = JdbcUtil.beginTransaction(dataSource, IsolationLevel.READ_COMMITTED);
            assertSame(outerTx.context(), joined.context(), "later work joins the transaction the command ran outside");
            joined.commit();
        } finally {
            outerTx.rollbackIfNotCommitted();
        }

        assertNull(TransactionContext.getTransaction(dataSource, SqlTransaction.CreatedBy.JDBC_UTIL));
    }

    // runOutsideTransaction(): cmd throws AND another transaction is opened inside → addSuppressed (line 763)
    @Test
    public void testRunOutsideTransaction_WhenCmdThrowsAndAnotherTransactionOpened_AddSuppressesISE() throws Exception {
        final AtomicReference<SqlTransaction> opened = new AtomicReference<>();
        clearThreadTransactionMap();
        final SqlTransaction outerTx = JdbcUtil.beginTransaction(dataSource, IsolationLevel.READ_COMMITTED);
        try {
            final RuntimeException ex = assertThrows(RuntimeException.class, () -> outerTx.runOutsideTransaction(() -> {
                opened.set(JdbcUtil.beginTransaction(dataSource, IsolationLevel.READ_COMMITTED));
                throw new RuntimeException("cmd error");
            }));
            assertTrue(ex.getSuppressed().length > 0);
            assertTrue(ex.getSuppressed()[0] instanceof IllegalStateException);
        } finally {
            final SqlTransaction inner = opened.get();
            if (inner != null) {
                inner.rollbackIfNotCommitted();
            }
        }
    }

    // callOutsideTransaction(): cmd throws AND another transaction is opened inside → addSuppressed (line 826)
    @Test
    public void testCallOutsideTransaction_WhenCmdThrowsAndAnotherTransactionOpened_AddSuppressesISE() throws Exception {
        final AtomicReference<SqlTransaction> opened = new AtomicReference<>();
        clearThreadTransactionMap();
        final SqlTransaction outerTx = JdbcUtil.beginTransaction(dataSource, IsolationLevel.READ_COMMITTED);
        try {
            final RuntimeException ex = assertThrows(RuntimeException.class, () -> outerTx.callOutsideTransaction(() -> {
                opened.set(JdbcUtil.beginTransaction(dataSource, IsolationLevel.READ_COMMITTED));
                throw new RuntimeException("cmd error");
            }));
            assertTrue(ex.getSuppressed().length > 0);
            assertTrue(ex.getSuppressed()[0] instanceof IllegalStateException);
        } finally {
            final SqlTransaction inner = opened.get();
            if (inner != null) {
                inner.rollbackIfNotCommitted();
            }
        }
    }

    private static void clearThreadTransactionMap() throws Exception {
        final Field mapField = TransactionContext.class.getDeclaredField("threadTransactionMap");
        mapField.setAccessible(true);
        ((java.util.Map<?, ?>) mapField.get(null)).clear();
    }

    @Test
    public void testResetConnectionContinuesAfterSetAutoCommitFails() throws SQLException {
        when(dataSource.getConnection()).thenReturn(connection);
        when(connection.getAutoCommit()).thenReturn(true);
        when(connection.getTransactionIsolation()).thenReturn(Connection.TRANSACTION_READ_COMMITTED);

        final SQLException autoCommitEx = new SQLException("autoCommit reset failed");
        doThrow(autoCommitEx).when(connection).setAutoCommit(true);

        final SqlTransaction tx = new TransactionContext(dataSource, connection, IsolationLevel.DEFAULT, SqlTransaction.CreatedBy.JDBC_UTIL, true, false)
                .enterScope(IsolationLevel.DEFAULT);

        tx.rollback();
        verify(connection).setAutoCommit(true);
        verify(connection).setTransactionIsolation(Connection.TRANSACTION_READ_COMMITTED);
        verify(connection).close();
    }

    // A participating transaction whose rollback can't mark the Spring transaction rollback-only: Spring may still commit the
    // work, so the status is FAILED_ROLLBACK (it used to be ROLLED_BACK) and the locally recorded rollback actions are not run
    // (they used to be, losing e.g. generated IDs of rows Spring then committed). The scope is still completed: the connection
    // reference goes back to Spring, the registry entry is gone, the after-rollback action runs, and later cleanup calls are
    // no-ops that don't try again.
    @Test
    public void testParticipatingRollbackIfNotCommittedLeavesTheOutcomeToSpringWhenItCannotBeMarkedRollbackOnly() throws SQLException {
        assertParticipatingRollbackLeavesTheOutcomeToSpringWhenTheMarkFails(false);
    }

    @Test
    public void testParticipatingRollbackLeavesTheOutcomeToSpringWhenItCannotBeMarkedRollbackOnly() throws SQLException {
        assertParticipatingRollbackLeavesTheOutcomeToSpringWhenTheMarkFails(true);
    }

    private void assertParticipatingRollbackLeavesTheOutcomeToSpringWhenTheMarkFails(final boolean explicitRollback) throws SQLException {
        final SqlTransaction transaction = participatingContext(connection).enterScope(IsolationLevel.DEFAULT);
        TransactionContext.putTransaction(transaction.context());

        final AtomicInteger undone = new AtomicInteger();
        final IllegalStateException markFailure = new IllegalStateException("cannot mark");

        try (MockedStatic<JdbcUtil> jdbcUtil = Mockito.mockStatic(JdbcUtil.class)) {
            // Spring's synchronization is not active: the action is recorded in the transaction's own log.
            jdbcUtil.when(() -> JdbcUtil.registerSpringRollbackAction(ArgumentMatchers.same(dataSource), ArgumentMatchers.any())).thenReturn(false);
            jdbcUtil.when(() -> JdbcUtil.markSpringTransactionRollbackOnly(dataSource)).thenThrow(markFailure);

            transaction.context().addRollbackAction(undone::incrementAndGet);
            assertSame(transaction.context(), TransactionContext.getTransaction(dataSource, SqlTransaction.CreatedBy.JDBC_UTIL));

            final IllegalStateException thrown = assertThrows(IllegalStateException.class, () -> {
                if (explicitRollback) {
                    transaction.rollback();
                } else {
                    transaction.rollbackIfNotCommitted();
                }
            });
            assertSame(markFailure, thrown);

            assertEquals(SqlTransaction.Status.FAILED_ROLLBACK, transaction.status());
            assertEquals(0, undone.get(), "the in-memory changes are left to the Spring transaction's outcome");
            assertNull(TransactionContext.getTransaction(dataSource, SqlTransaction.CreatedBy.JDBC_UTIL));
            jdbcUtil.verify(() -> JdbcUtil.releaseSpringManagedConnection(connection, dataSource));
            jdbcUtil.verify(() -> JdbcUtil.markSpringTransactionRollbackOnly(dataSource));

            // The paired cleanup of the finally idiom, and close(): no retry, no second completion.
            assertDoesNotThrow(transaction::rollbackIfNotCommitted);
            assertDoesNotThrow(transaction::close);
            assertEquals(SqlTransaction.Status.FAILED_ROLLBACK, transaction.status());
            assertEquals(0, undone.get());
            jdbcUtil.verify(() -> JdbcUtil.markSpringTransactionRollbackOnly(dataSource), times(1));
            jdbcUtil.verify(() -> JdbcUtil.releaseSpringManagedConnection(connection, dataSource), times(1));
            jdbcUtil.verify(() -> JdbcUtil.releaseConnection(ArgumentMatchers.any(), ArgumentMatchers.any()), never());
        }

        // Spring owns the connection's state: nothing on it was touched.
        verify(connection, never()).rollback();
        verify(connection, never()).commit();
        verify(connection, never()).close();
        verify(connection, never()).setAutoCommit(ArgumentMatchers.anyBoolean());
        verify(connection, never()).setTransactionIsolation(ArgumentMatchers.anyInt());
    }

    // The success path, unchanged: a participating rollback that marks the Spring transaction ends ROLLED_BACK and runs the
    // locally recorded rollback actions right away.
    @Test
    public void testParticipatingRollbackRunsTheRollbackActionsOnceTheSpringTransactionIsMarked() throws SQLException {
        final SqlTransaction transaction = participatingContext(connection).enterScope(IsolationLevel.DEFAULT);
        TransactionContext.putTransaction(transaction.context());

        final AtomicInteger undone = new AtomicInteger();

        try (MockedStatic<JdbcUtil> jdbcUtil = Mockito.mockStatic(JdbcUtil.class)) {
            jdbcUtil.when(() -> JdbcUtil.registerSpringRollbackAction(ArgumentMatchers.same(dataSource), ArgumentMatchers.any())).thenReturn(false);
            // markSpringTransactionRollbackOnly, not stubbed, returns normally: the mark reached the Spring transaction.

            transaction.context().addRollbackAction(undone::incrementAndGet);
            transaction.rollbackIfNotCommitted();

            assertEquals(SqlTransaction.Status.ROLLED_BACK, transaction.status());
            assertEquals(1, undone.get());
            assertNull(TransactionContext.getTransaction(dataSource, SqlTransaction.CreatedBy.JDBC_UTIL));
            jdbcUtil.verify(() -> JdbcUtil.markSpringTransactionRollbackOnly(dataSource));
            jdbcUtil.verify(() -> JdbcUtil.releaseSpringManagedConnection(connection, dataSource));
        }

        verify(connection, never()).rollback();
        verify(connection, never()).close();
    }

    // External review (P1): without Spring's synchronization, a participating transaction records its rollback actions itself.
    // A nested scope's rollback used to run them right away, before the outermost scope even tried to mark the Spring
    // transaction rollback-only: if that mark then failed, Spring could commit the rows of entities that had lost their IDs.
    // They are left to the outermost scope now (the explicit rollback() and the cleanup call of nested scopes alike): run once
    // the mark is set, discarded if it fails.
    @Test
    public void testParticipatingNestedRollbackLeavesTheRollbackActionsToTheOutermostMark() throws SQLException {
        assertParticipatingNestedRollbackLeavesTheRollbackActionsToTheOutermostMark(true);
    }

    @Test
    public void testParticipatingNestedRollbackDiscardsTheRollbackActionsWhenTheOutermostMarkFails() throws SQLException {
        assertParticipatingNestedRollbackLeavesTheRollbackActionsToTheOutermostMark(false);
    }

    @SuppressWarnings("deprecation")
    private void assertParticipatingNestedRollbackLeavesTheRollbackActionsToTheOutermostMark(final boolean markable) throws SQLException {
        final SqlTransaction outer = participatingContext(connection).enterScope(IsolationLevel.DEFAULT);
        TransactionContext.putTransaction(outer.context());

        final AtomicInteger undone = new AtomicInteger();
        final IllegalStateException markFailure = new IllegalStateException("cannot mark");

        try (MockedStatic<JdbcUtil> jdbcUtil = Mockito.mockStatic(JdbcUtil.class)) {
            jdbcUtil.when(() -> JdbcUtil.registerSpringRollbackAction(ArgumentMatchers.same(dataSource), ArgumentMatchers.any())).thenReturn(false);

            if (!markable) {
                jdbcUtil.when(() -> JdbcUtil.markSpringTransactionRollbackOnly(dataSource)).thenThrow(markFailure);
            }

            final SqlTransaction middle = outer.context().enterScope(IsolationLevel.DEFAULT);
            middle.context().addRollbackAction(undone::incrementAndGet);
            final SqlTransaction inner = outer.context().enterScope(IsolationLevel.DEFAULT);
            inner.context().addRollbackAction(undone::incrementAndGet);

            inner.rollback();
            middle.rollbackIfNotCommitted();

            assertEquals(SqlTransaction.Status.MARKED_ROLLBACK, outer.status());
            assertEquals(0, undone.get(), "Spring decides the outcome until the outermost scope has marked its transaction");
            jdbcUtil.verify(() -> JdbcUtil.markSpringTransactionRollbackOnly(dataSource), never());

            if (markable) {
                // The Spring transaction has only been marked: it rolls back when it completes, so the message claims no rollback.
                final RollbackOnlyException thrown = assertThrows(RollbackOnlyException.class, outer::commit);
                assertTrue(thrown.getMessage().contains("marked it rollback-only")
                        && thrown.getMessage().contains("will be rolled back by the Spring-managed transaction it participates in")
                        && !thrown.getMessage().contains("has been rolled back"), thrown.getMessage());
                assertEquals(SqlTransaction.Status.ROLLED_BACK, outer.status());
                assertEquals(2, undone.get());
            } else {
                final IllegalStateException thrown = assertThrows(IllegalStateException.class, outer::commit);
                assertSame(markFailure, thrown);
                assertReasonAttached(thrown, "marked it rollback-only", false);
                assertEquals(SqlTransaction.Status.FAILED_ROLLBACK, outer.status());
                assertEquals(0, undone.get(), "Spring may still commit the work");
            }

            assertNull(TransactionContext.getTransaction(dataSource, SqlTransaction.CreatedBy.JDBC_UTIL));
            jdbcUtil.verify(() -> JdbcUtil.markSpringTransactionRollbackOnly(dataSource), times(1));
            jdbcUtil.verify(() -> JdbcUtil.releaseSpringManagedConnection(connection, dataSource));
        }

        verify(connection, never()).rollback();
        verify(connection, never()).commit();
    }

    @Test
    public void testResetConnectionContinuesAfterSetIsolationFails() throws SQLException {
        when(dataSource.getConnection()).thenReturn(connection);
        when(connection.getAutoCommit()).thenReturn(true);
        when(connection.getTransactionIsolation()).thenReturn(Connection.TRANSACTION_READ_COMMITTED);

        final SQLException isoEx = new SQLException("isolation reset failed");
        doThrow(isoEx).when(connection).setTransactionIsolation(ArgumentMatchers.anyInt());

        final SqlTransaction tx = new TransactionContext(dataSource, connection, IsolationLevel.DEFAULT, SqlTransaction.CreatedBy.JDBC_UTIL, true, false)
                .enterScope(IsolationLevel.DEFAULT);

        tx.rollback();
        verify(connection).setAutoCommit(true);
        verify(connection).close();
    }

    // ---- Failure-during-beginTransaction must restore the connection's original state ----
    // Regression: SqlTransaction's constructor mutates the connection (autoCommit=false, then setTransactionIsolation)
    // BEFORE the SqlTransaction is published. If either the constructor's isolation change or the subsequent
    // opening of the outermost scope threw, the partially-mutated connection used to be released back to the pool dirty.
    // The fix: (1) make the constructor's mutations atomic (restore-on-failure); (2) on a post-construction
    // failure in JdbcUtil.beginTransaction, call TransactionContext.resetAndCloseConnection to restore before release.

    @Test
    public void testBeginTransaction_ConstructorIsolationFailure_RestoresConnectionState() throws SQLException {
        // Connection accepts setAutoCommit(false), then throws on the requested isolation change.
        final int serializable = IsolationLevel.SERIALIZABLE.intValue();
        doThrow(new SQLException("isolation rejected")).when(connection).setTransactionIsolation(serializable);

        assertThrows(UncheckedSQLException.class, () -> JdbcUtil.beginTransaction(dataSource, IsolationLevel.SERIALIZABLE));

        // Constructor's atomic restore must have called setAutoCommit back to the captured original value (true).
        verify(connection).setAutoCommit(false);
        verify(connection).setAutoCommit(true);
    }

    @Test
    public void testBeginTransaction_OutermostScope_AppliesIsolationExactlyOnce() throws SQLException {
        // The constructor applies the requested isolation; opening the outermost scope must NOT
        // re-issue the same setTransactionIsolation (redundant driver round-trip). A doThrow on a
        // hypothetical second call proves the outermost begin never makes one.
        final int serializable = IsolationLevel.SERIALIZABLE.intValue();
        doNothing().doThrow(new SQLException("unexpected second isolation call")).when(connection).setTransactionIsolation(serializable);

        final SqlTransaction tran = JdbcUtil.beginTransaction(dataSource, IsolationLevel.SERIALIZABLE);

        verify(connection, times(1)).setTransactionIsolation(serializable);

        // Ending the outermost scope restores the captured original isolation level.
        tran.rollbackIfNotCommitted();
        verify(connection).setTransactionIsolation(Connection.TRANSACTION_READ_COMMITTED);
    }

    @Test
    public void testNestedExit_SameIsolationDoesNotResetConnection() throws SQLException {
        final SqlTransaction tran = JdbcUtil.beginTransaction(dataSource, IsolationLevel.READ_COMMITTED);
        final SqlTransaction nested = tran.context().enterScope(IsolationLevel.READ_COMMITTED);

        clearInvocations(connection);
        doThrow(new SQLException("same isolation must not be reset")).when(connection).setTransactionIsolation(Connection.TRANSACTION_READ_COMMITTED);

        assertDoesNotThrow(() -> nested.commit());
        assertEquals(1, tran.context().depth());
        verify(connection, never()).setTransactionIsolation(ArgumentMatchers.anyInt());

        // Let the outermost rollback restore the connection captured at transaction creation.
        doNothing().when(connection).setTransactionIsolation(Connection.TRANSACTION_READ_COMMITTED);
        tran.rollbackIfNotCommitted();
    }

    // Regression: an outer DEFAULT scope runs at the connection's original level (READ_COMMITTED here). A
    // nested scope explicitly requesting that same level compared DEFAULT != READ_COMMITTED and re-issued
    // setTransactionIsolation on entry and exit, which drivers that reject isolation changes once the
    // transaction has started (e.g. PostgreSQL) turn into a spurious failure of the nested begin.
    @Test
    public void testNestedExplicitLevelEqualToOuterDefaultDoesNotTouchConnectionIsolation() throws SQLException {
        final SqlTransaction outer = JdbcUtil.beginTransaction(dataSource, IsolationLevel.DEFAULT);

        clearInvocations(connection);
        doThrow(new SQLException("cannot change isolation in the middle of a transaction")).when(connection).setTransactionIsolation(ArgumentMatchers.anyInt());

        try {
            final SqlTransaction nested = assertDoesNotThrow(() -> JdbcUtil.beginTransaction(dataSource, IsolationLevel.READ_COMMITTED));
            assertSame(outer.context(), nested.context());
            assertEquals(IsolationLevel.READ_COMMITTED, nested.isolationLevel());

            assertDoesNotThrow(() -> nested.commit());
            nested.rollbackIfNotCommitted();
            assertEquals(IsolationLevel.DEFAULT, outer.isolationLevel());
            verify(connection, never()).setTransactionIsolation(ArgumentMatchers.anyInt());
        } finally {
            doNothing().when(connection).setTransactionIsolation(ArgumentMatchers.anyInt());
            outer.rollbackIfNotCommitted();
        }
    }

    // Guard for the resolved (physical) comparison: under an outer DEFAULT scope (connection original =
    // READ_COMMITTED), only a scope whose level differs from the level the connection is PHYSICALLY at is
    // applied. A deeper READ_COMMITTED under SERIALIZABLE must still be applied and SERIALIZABLE restored on
    // its exit; leaving SERIALIZABLE for the READ_COMMITTED scope needs a call, leaving that scope for the
    // DEFAULT outer does not; and the final close still restores the original level exactly once.
    @Test
    public void testNestedLevelsUnderOuterDefaultApplyOnlyPhysicalChanges() throws SQLException {
        final SqlTransaction outer = JdbcUtil.beginTransaction(dataSource, IsolationLevel.DEFAULT);
        clearInvocations(connection);

        final SqlTransaction n1 = JdbcUtil.beginTransaction(dataSource, IsolationLevel.READ_COMMITTED); // physically RC already: no call
        final SqlTransaction n2 = JdbcUtil.beginTransaction(dataSource, IsolationLevel.SERIALIZABLE); // RC -> SER
        final SqlTransaction n3 = JdbcUtil.beginTransaction(dataSource, IsolationLevel.READ_COMMITTED); // SER -> RC
        final SqlTransaction n4 = JdbcUtil.beginTransaction(dataSource, IsolationLevel.DEFAULT); // inherits RC: no call
        assertSame(outer.context(), n4.context());
        assertEquals(IsolationLevel.READ_COMMITTED, n4.isolationLevel());

        n4.commit(); // RC -> RC: no call
        n4.rollbackIfNotCommitted();
        n3.commit(); // RC -> SER
        n3.rollbackIfNotCommitted();
        assertEquals(IsolationLevel.SERIALIZABLE, outer.context().isolationLevel());
        n2.commit(); // SER -> RC
        n2.rollbackIfNotCommitted();
        n1.commit(); // RC -> DEFAULT (= RC): no call
        n1.rollbackIfNotCommitted();
        assertEquals(IsolationLevel.DEFAULT, outer.isolationLevel());

        outer.rollbackIfNotCommitted(); // rollback, then the final reset to the original level

        final InOrder order = inOrder(connection);
        order.verify(connection).setTransactionIsolation(Connection.TRANSACTION_SERIALIZABLE);
        order.verify(connection).setTransactionIsolation(Connection.TRANSACTION_READ_COMMITTED);
        order.verify(connection).setTransactionIsolation(Connection.TRANSACTION_SERIALIZABLE);
        order.verify(connection).setTransactionIsolation(Connection.TRANSACTION_READ_COMMITTED);
        order.verify(connection).rollback();
        order.verify(connection).setTransactionIsolation(Connection.TRANSACTION_READ_COMMITTED);
        verify(connection, times(2)).setTransactionIsolation(Connection.TRANSACTION_SERIALIZABLE);
        verify(connection, times(3)).setTransactionIsolation(Connection.TRANSACTION_READ_COMMITTED);
        assertEquals(SqlTransaction.Status.ROLLED_BACK, outer.status());
    }

    // A nested scope whose isolation level the connection rejects is not opened: the enclosing scope stays the innermost
    // one, with its own isolation level, and can still commit.
    @Test
    public void testEnterScope_NestedFailure_OpensNoScope() throws Exception {
        final SqlTransaction outer = JdbcUtil.beginTransaction(dataSource, IsolationLevel.READ_COMMITTED);

        // Outer scope at READ_COMMITTED. Now arrange that the NESTED scope's setTransactionIsolation throws.
        doThrow(new SQLException("nested-isolation-fail")).when(connection).setTransactionIsolation(IsolationLevel.SERIALIZABLE.intValue());

        assertThrows(UncheckedSQLException.class, () -> outer.context().enterScope(IsolationLevel.SERIALIZABLE));

        // Outer scope must remain fully intact: no scope was opened.
        assertEquals(IsolationLevel.READ_COMMITTED, outer.context().isolationLevel());
        assertEquals(1, outer.context().depth());

        // Outer commit must succeed cleanly without "isolation stack empty" errors.
        Mockito.reset(connection);
        when(connection.getAutoCommit()).thenReturn(true);
        when(connection.getTransactionIsolation()).thenReturn(Connection.TRANSACTION_READ_COMMITTED);
        outer.commit();
    }

    @Test
    public void testEnterScope_NestedFailure_RestoresOuterConnectionIsolation() throws Exception {
        final SqlTransaction outer = JdbcUtil.beginTransaction(dataSource, IsolationLevel.READ_COMMITTED);
        clearInvocations(connection);

        // Some drivers can change their state and still report a failure. The failed nested entry
        // must therefore make a best-effort call that restores the outer scope's isolation.
        doThrow(new SQLException("nested-isolation-fail")).doNothing().when(connection).setTransactionIsolation(IsolationLevel.SERIALIZABLE.intValue());

        assertThrows(UncheckedSQLException.class, () -> outer.context().enterScope(IsolationLevel.SERIALIZABLE));

        final InOrder inOrder = inOrder(connection);
        inOrder.verify(connection).setTransactionIsolation(IsolationLevel.SERIALIZABLE.intValue());
        inOrder.verify(connection).setTransactionIsolation(IsolationLevel.READ_COMMITTED.intValue());
        assertEquals(IsolationLevel.READ_COMMITTED, outer.isolationLevel());

        outer.rollbackIfNotCommitted();
    }

    // A rejected nested begin after a nested commit() must leave that committed scope's cleanup a no-op: it must not exit
    // (and roll back) the enclosing transaction.
    @Test
    public void testEnterScope_NestedFailure_KeepsCommittedScopeCleanupNoOp() throws Exception {
        final SqlTransaction outer = JdbcUtil.beginTransaction(dataSource, IsolationLevel.READ_COMMITTED);
        final SqlTransaction inner = JdbcUtil.beginTransaction(dataSource);
        assertSame(outer.context(), inner.context());

        inner.commit(); // nested: deferred to the outer scope; completes inner, so its cleanup is a no-op

        doThrow(new SQLException("nested-isolation-fail")).when(connection).setTransactionIsolation(IsolationLevel.SERIALIZABLE.intValue());
        assertThrows(UncheckedSQLException.class, () -> JdbcUtil.beginTransaction(dataSource, IsolationLevel.SERIALIZABLE));

        inner.rollbackIfNotCommitted(); // still the no-op paired with inner.commit()

        assertEquals(SqlTransaction.Status.ACTIVE, outer.status());
        verify(connection, never()).rollback();

        outer.commit();

        assertEquals(SqlTransaction.Status.COMMITTED, outer.status());
        verify(connection).commit();
        verify(connection, never()).rollback();
    }

    // A nested scope opened AND completed between a scope's commit() and its finally block (e.g. a transactional DAO call
    // made after commit()) must not turn that finally block into a real exit that rolls back the whole transaction.
    @Test
    public void testScopeCompletedAfterNestedCommitKeepsThatCommitsCleanupNoOp() throws Exception {
        final SqlTransaction outer = JdbcUtil.beginTransaction(dataSource, IsolationLevel.READ_COMMITTED);

        try {
            final SqlTransaction inner = JdbcUtil.beginTransaction(dataSource);
            assertSame(outer.context(), inner.context());

            try {
                inner.commit(); // nested: deferred to the outer scope

                // More work in inner's try block, running its own properly paired scope on the same transaction.
                JdbcUtil.runInTransaction(dataSource, () -> {
                });
            } finally {
                inner.rollbackIfNotCommitted(); // must stay the no-op paired with inner.commit()
            }

            assertEquals(SqlTransaction.Status.ACTIVE, outer.status());
            assertSame(outer.context(), TransactionContext.getTransaction(dataSource, SqlTransaction.CreatedBy.JDBC_UTIL));
            verify(connection, never()).rollback();

            outer.commit();
        } finally {
            outer.rollbackIfNotCommitted();
        }

        assertEquals(SqlTransaction.Status.COMMITTED, outer.status());
        verify(connection).commit();
        verify(connection, never()).rollback();
    }

    // A paired scope that FAILS after a nested commit() marks the transaction rollback-only, but the committed
    // scope's finally block must still be its no-op so the rollback is left to the outermost scope. Pre-fix
    // that finally block exited the outer scope and rolled back early.
    @Test
    public void testScopeFailedAfterNestedCommitLeavesRollbackToOutermostScope() throws Exception {
        final SqlTransaction outer = JdbcUtil.beginTransaction(dataSource, IsolationLevel.READ_COMMITTED);
        final RuntimeException failure = new RuntimeException("work after nested commit failed");

        try {
            final SqlTransaction inner = JdbcUtil.beginTransaction(dataSource);
            assertSame(outer.context(), inner.context());

            final RuntimeException thrown = assertThrows(RuntimeException.class, () -> {
                try {
                    inner.commit();

                    JdbcUtil.runInTransaction(dataSource, () -> {
                        throw failure;
                    });
                } finally {
                    inner.rollbackIfNotCommitted(); // must stay the no-op paired with inner.commit()
                }
            });
            assertSame(failure, thrown);

            assertEquals(SqlTransaction.Status.MARKED_ROLLBACK, outer.status());
            assertSame(outer.context(), TransactionContext.getTransaction(dataSource, SqlTransaction.CreatedBy.JDBC_UTIL));
            verify(connection, never()).rollback();
        } finally {
            outer.rollbackIfNotCommitted(); // the outer scope's failure-path cleanup performs the real rollback
        }

        assertEquals(SqlTransaction.Status.ROLLED_BACK, outer.status());
        assertTrue(TransactionContext.getTransaction(dataSource, SqlTransaction.CreatedBy.JDBC_UTIL) == null);
        verify(connection).rollback();
        verify(connection, never()).commit();
    }

    // The cleanup no-op is tracked per nesting level, so at every level it survives scopes opened and completed
    // after that level's commit() - including through try-with-resources close().
    @Test
    public void testCleanupNoOpsSurviveScopesCompletedAfterCommitAtEveryLevel() throws Exception {
        final SqlTransaction outer = JdbcUtil.beginTransaction(dataSource, IsolationLevel.READ_COMMITTED);

        try (outer) {
            try (SqlTransaction middle = JdbcUtil.beginTransaction(dataSource)) {
                try (SqlTransaction inner = JdbcUtil.beginTransaction(dataSource)) {
                    assertSame(outer.context(), middle.context());
                    assertSame(outer.context(), inner.context());

                    inner.commit();
                    JdbcUtil.runInTransaction(dataSource, () -> {
                    });
                } // inner.close(): the no-op paired with inner.commit()

                middle.commit();
                JdbcUtil.callInTransaction(dataSource, () -> { // two nested levels after middle.commit()
                    JdbcUtil.runInTransaction(dataSource, () -> {
                    });
                    return null;
                });
            } // middle.close(): the no-op paired with middle.commit()

            assertEquals(SqlTransaction.Status.ACTIVE, outer.status());
            assertSame(outer.context(), TransactionContext.getTransaction(dataSource, SqlTransaction.CreatedBy.JDBC_UTIL));
            verify(connection, never()).rollback();

            outer.commit();
        }

        assertEquals(SqlTransaction.Status.COMMITTED, outer.status());
        verify(dataSource, times(1)).getConnection();
        verify(connection).commit();
        verify(connection, never()).rollback();
    }

    // A nested commit() whose paired cleanup never runs (misuse) completes its scope anyway, so a later scope failing at
    // that depth still really exits and marks the transaction rollback-only instead of leaving its scope open.
    @Test
    public void testUnpairedNestedCommitDoesNotAffectLaterScopes() throws Exception {
        final SqlTransaction outer = JdbcUtil.beginTransaction(dataSource, IsolationLevel.READ_COMMITTED);

        try {
            final SqlTransaction middle = JdbcUtil.beginTransaction(dataSource);

            try {
                JdbcUtil.beginTransaction(dataSource).commit(); // no paired rollbackIfNotCommitted()
                middle.commit();
            } finally {
                middle.rollbackIfNotCommitted();
            }

            final RuntimeException failure = new RuntimeException("later scope failed");
            assertSame(failure, assertThrows(RuntimeException.class, () -> JdbcUtil.runInTransaction(dataSource, () -> {
                throw failure;
            })));

            assertEquals(SqlTransaction.Status.MARKED_ROLLBACK, outer.status());
            verify(connection, never()).rollback();
        } finally {
            outer.rollbackIfNotCommitted();
        }

        assertEquals(SqlTransaction.Status.ROLLED_BACK, outer.status());
        assertTrue(TransactionContext.getTransaction(dataSource, SqlTransaction.CreatedBy.JDBC_UTIL) == null);
        verify(connection).rollback();
    }

    @Test
    public void testEnterScope_UncheckedFailureOpensNoScope() throws Exception {
        final SqlTransaction outer = JdbcUtil.beginTransaction(dataSource, IsolationLevel.READ_COMMITTED);
        final AssertionError mutationFailure = new AssertionError("unchecked isolation failure");
        final IllegalStateException restoreFailure = new IllegalStateException("unchecked isolation restore failure");
        doThrow(mutationFailure).when(connection).setTransactionIsolation(IsolationLevel.SERIALIZABLE.intValue());
        doThrow(restoreFailure).when(connection).setTransactionIsolation(IsolationLevel.READ_COMMITTED.intValue());

        final AssertionError thrown = assertThrows(AssertionError.class, () -> outer.context().enterScope(IsolationLevel.SERIALIZABLE));

        assertSame(mutationFailure, thrown);
        assertArrayEquals(new Throwable[] { restoreFailure }, thrown.getSuppressed());
        assertEquals(IsolationLevel.READ_COMMITTED, outer.context().isolationLevel());
        assertEquals(1, outer.context().depth());

        Mockito.reset(connection);
        outer.rollbackIfNotCommitted();
    }

    // A nested exit whose isolation restore fails keeps the nested scope open and current: the transaction's effective
    // isolation level stays the nested scope's, consistent with the open scopes.
    @Test
    public void testNestedExit_RestoreFailure_KeepsScopeOpenAndCurrent() throws Exception {
        final SqlTransaction tran = JdbcUtil.beginTransaction(dataSource, IsolationLevel.READ_COMMITTED);
        final SqlTransaction nested = tran.context().enterScope(IsolationLevel.SERIALIZABLE);

        // On exit, the nested scope tries to restore READ_COMMITTED on the connection. Make THAT call throw.
        doThrow(new SQLException("restore-fail")).when(connection).setTransactionIsolation(Connection.TRANSACTION_READ_COMMITTED);

        assertThrows(UncheckedSQLException.class, nested::commit);

        // After the failure the nested scope is still open and current, with its own settings.
        assertEquals(IsolationLevel.SERIALIZABLE, tran.context().isolationLevel());
        assertEquals(2, tran.context().depth());
    }

    @Test
    public void testNestedExit_UncheckedRestoreFailureKeepsScopeOpen() throws Exception {
        final SqlTransaction transaction = JdbcUtil.beginTransaction(dataSource, IsolationLevel.READ_COMMITTED);
        final SqlTransaction nested = transaction.context().enterScope(IsolationLevel.SERIALIZABLE);
        final AssertionError restoreFailure = new AssertionError("unchecked restore failure");
        doThrow(restoreFailure).when(connection).setTransactionIsolation(Connection.TRANSACTION_READ_COMMITTED);

        final AssertionError thrown = assertThrows(AssertionError.class, nested::commit);

        assertSame(restoreFailure, thrown);
        assertEquals(IsolationLevel.SERIALIZABLE, transaction.context().isolationLevel());
        assertEquals(2, transaction.context().depth());

        Mockito.reset(connection);
        nested.rollbackIfNotCommitted();
        transaction.rollbackIfNotCommitted();
    }

    @Test
    public void testRollback_DoesNotResetConnectionAfterRollbackSqlFailure() throws SQLException {
        final SqlTransaction tran = JdbcUtil.beginTransaction(dataSource, IsolationLevel.READ_COMMITTED);
        doThrow(new SQLException("rollback-boom")).when(connection).rollback();
        doThrow(new AssertionError("cleanup-error")).when(connection).setAutoCommit(true);

        final UncheckedSQLException ex = assertThrows(UncheckedSQLException.class, tran::rollback);

        assertEquals("rollback-boom", ex.getCause().getMessage());
        assertEquals(0, ex.getSuppressed().length);
        verify(connection, never()).setAutoCommit(true);
        verify(connection).close();
    }

    @Test
    public void testRollback_SharedRollbackAndCleanupFailureDoesNotSelfSuppress() throws SQLException {
        final SqlTransaction tran = JdbcUtil.beginTransaction(dataSource, IsolationLevel.READ_COMMITTED);
        final AssertionError sharedFailure = new AssertionError("shared rollback/cleanup failure");
        doThrow(sharedFailure).when(connection).rollback();
        doThrow(sharedFailure).when(connection).close();

        final AssertionError thrown = assertThrows(AssertionError.class, tran::rollback);

        assertSame(sharedFailure, thrown);
        assertEquals(0, thrown.getSuppressed().length);
    }

    @Tag("integration")
    @Test
    public void testFailedRollbackDoesNotImplicitlyCommitPendingWork() throws Exception {
        final String url = "jdbc:h2:mem:failed_rollback_" + System.nanoTime() + ";DB_CLOSE_DELAY=-1";

        try (Connection actual = java.sql.DriverManager.getConnection(url);
             Connection observer = java.sql.DriverManager.getConnection(url)) {
            JdbcUtil.executeUpdate(actual, "CREATE TABLE pending_work(id INT)");
            final Connection failingConnection = Mockito.mock(Connection.class, org.mockito.AdditionalAnswers.delegatesTo(actual));
            final SqlTransaction transaction = new TransactionContext(null, failingConnection, IsolationLevel.DEFAULT, SqlTransaction.CreatedBy.JDBC_UTIL,
                    false, false).enterScope(IsolationLevel.DEFAULT);
            JdbcUtil.executeUpdate(actual, "INSERT INTO pending_work VALUES (1)");
            final SQLException rollbackFailure = new SQLException("rollback unavailable");
            doThrow(rollbackFailure).when(failingConnection).rollback();

            try {
                final UncheckedSQLException thrown = assertThrows(UncheckedSQLException.class, transaction::rollbackIfNotCommitted);
                assertSame(rollbackFailure, thrown.getCause());
                assertEquals(SqlTransaction.Status.FAILED_ROLLBACK, transaction.status());
                assertFalse(actual.getAutoCommit());
                assertEquals(0, JdbcUtil.prepareQuery(observer, "SELECT COUNT(*) FROM pending_work").queryForInt().orElse(-1));
                verify(failingConnection, never()).setAutoCommit(true);
                verify(failingConnection, never()).setTransactionIsolation(org.mockito.ArgumentMatchers.anyInt());
            } finally {
                actual.rollback();
            }
        }
    }

    @Test
    public void testFailedRollbackReleasesOwnedConnectionWithoutRestoringState() throws SQLException {
        final SqlTransaction transaction = JdbcUtil.beginTransaction(dataSource);
        clearInvocations(connection);
        doThrow(new SQLException("rollback unavailable")).when(connection).rollback();

        assertThrows(UncheckedSQLException.class, transaction::rollbackIfNotCommitted);

        verify(connection).close();
        verify(connection, never()).setAutoCommit(org.mockito.ArgumentMatchers.anyBoolean());
        verify(connection, never()).setTransactionIsolation(org.mockito.ArgumentMatchers.anyInt());
    }

    @Test
    public void testCommit_SharedConnectionResetFailureDoesNotSelfSuppress() throws SQLException {
        final SqlTransaction tran = JdbcUtil.beginTransaction(dataSource, IsolationLevel.READ_COMMITTED);
        final AssertionError sharedFailure = new AssertionError("shared reset failure");
        doThrow(sharedFailure).when(connection).setAutoCommit(true);
        doThrow(sharedFailure).when(connection).setTransactionIsolation(Connection.TRANSACTION_READ_COMMITTED);

        final AssertionError thrown = assertThrows(AssertionError.class, tran::commit);

        assertSame(sharedFailure, thrown);
        assertEquals(0, thrown.getSuppressed().length);
    }

    @Test
    public void testCommitPropagatesTheConnectionCleanupFailureAfterTheCommit() throws SQLException {
        final SqlTransaction transaction = JdbcUtil.beginTransaction(dataSource);
        final IllegalStateException cleanupFailure = new IllegalStateException("connection reset failed");
        doThrow(cleanupFailure).when(connection).setAutoCommit(true);

        final IllegalStateException thrown = assertThrows(IllegalStateException.class, transaction::commit);

        assertSame(cleanupFailure, thrown);
        assertEquals(0, thrown.getSuppressed().length);
        assertEquals(SqlTransaction.Status.COMMITTED, transaction.status());
        verify(connection).commit();
        verify(connection).close();
        verify(connection, never()).rollback();
    }

    @Test
    public void testRollbackPropagatesTheConnectionCleanupFailureAfterTheRollback() throws SQLException {
        final SqlTransaction transaction = JdbcUtil.beginTransaction(dataSource);
        final AssertionError cleanupFailure = new AssertionError("connection reset failed");
        doThrow(cleanupFailure).when(connection).setAutoCommit(true);

        final AssertionError thrown = assertThrows(AssertionError.class, transaction::rollback);

        assertSame(cleanupFailure, thrown);
        assertEquals(0, thrown.getSuppressed().length);
        assertEquals(SqlTransaction.Status.ROLLED_BACK, transaction.status());
        verify(connection).rollback();
        verify(connection).close();
    }

    @Test
    public void testCompletionPropagatesTheCleanupFailureOfACommitOrRollback() throws SQLException {
        for (final boolean commit : new boolean[] { false, true }) {
            final DataSource localDataSource = Mockito.mock(DataSource.class);
            final Connection localConnection = Mockito.mock(Connection.class);
            when(localDataSource.getConnection()).thenReturn(localConnection);
            when(localConnection.getAutoCommit()).thenReturn(true);
            when(localConnection.getTransactionIsolation()).thenReturn(Connection.TRANSACTION_READ_COMMITTED);
            final SqlTransaction transaction = JdbcUtil.beginTransaction(localDataSource);
            final IllegalStateException cleanupFailure = new IllegalStateException("connection reset failed");
            doThrow(cleanupFailure).when(localConnection).setAutoCommit(true);

            final IllegalStateException thrown = assertThrows(IllegalStateException.class, commit ? transaction::commit : transaction::rollback);

            assertSame(cleanupFailure, thrown);
            assertEquals(0, thrown.getSuppressed().length);
            assertEquals(commit ? SqlTransaction.Status.COMMITTED : SqlTransaction.Status.ROLLED_BACK, transaction.status());
            verify(localConnection).close();
            verify(localConnection, times(commit ? 1 : 0)).commit();
            verify(localConnection, times(commit ? 0 : 1)).rollback();
        }
    }

    @Test
    public void testRollbackPreservesPrimaryFailureWithACleanupFailure() throws SQLException {
        // Spring's release helper absorbs close failures; exercise the direct release path where an Error escapes.
        JdbcUtil.runIgnoringSpringTransaction(() -> {
            final SqlTransaction transaction = JdbcUtil.beginTransaction(dataSource);
            final SQLException rollbackFailure = new SQLException("rollback failed");
            final AssertionError cleanupFailure = new AssertionError("connection close failed");
            doThrow(rollbackFailure).when(connection).rollback();
            doThrow(cleanupFailure).when(connection).close();

            final UncheckedSQLException thrown = assertThrows(UncheckedSQLException.class, transaction::rollback);

            assertSame(rollbackFailure, thrown.getCause());
            // A later failure remains attached to the primary operation.
            assertArrayEquals(new Throwable[] { cleanupFailure }, thrown.getSuppressed());
            assertEquals(SqlTransaction.Status.FAILED_ROLLBACK, transaction.status());
            verify(connection).close();
            verify(connection, never()).setAutoCommit(true);
        });
    }

    // Round 14 review: a transaction hiding a participating one (see TransactionContext.putTransaction), hidden in turn by a
    // third one, dropped the participating transaction when it completed first (scopes completed out of order): still open, it
    // was no longer registered, so later work began a transaction of its own instead of joining it. (The registry keeps the
    // chain whether or not the transactions participate; transactions of their own keep the cleanup simple here.)
    @Test
    public void testAHiddenTransactionCompletedOutOfOrderKeepsTheOneItHid() throws SQLException {
        final SqlTransaction hidden = unregisteredScope();
        TransactionContext.putTransaction(hidden.context());
        final SqlTransaction middle = unregisteredScope();
        TransactionContext.putTransaction(middle.context());
        final SqlTransaction top = unregisteredScope();
        TransactionContext.putTransaction(top.context());

        try {
            middle.commit(); // before the one hiding it
            assertSame(top.context(), TransactionContext.getTransaction(dataSource, SqlTransaction.CreatedBy.JDBC_UTIL));

            top.commit();
            assertSame(hidden.context(), TransactionContext.getTransaction(dataSource, SqlTransaction.CreatedBy.JDBC_UTIL),
                    "the transaction the middle one hid is registered again");
        } finally {
            top.rollbackIfNotCommitted();
            middle.rollbackIfNotCommitted();
            hidden.rollbackIfNotCommitted();
        }

        assertNull(TransactionContext.getTransaction(dataSource, SqlTransaction.CreatedBy.JDBC_UTIL));
    }

    // The same for a transaction completed inside its own callOutsideTransaction, while it is not registered.
    @Test
    public void testATransactionCompletedInsideItsOwnCallOutsideTransactionKeepsTheOneItHid() throws SQLException {
        final SqlTransaction hidden = unregisteredScope();
        TransactionContext.putTransaction(hidden.context());
        final SqlTransaction hiding = unregisteredScope();
        TransactionContext.putTransaction(hiding.context());

        try {
            hiding.callOutsideTransaction(() -> {
                assertNull(TransactionContext.getTransaction(dataSource, SqlTransaction.CreatedBy.JDBC_UTIL));
                hiding.commit();
                return null;
            });

            assertSame(hidden.context(), TransactionContext.getTransaction(dataSource, SqlTransaction.CreatedBy.JDBC_UTIL));
        } finally {
            hiding.rollbackIfNotCommitted();
            hidden.rollbackIfNotCommitted();
        }

        assertNull(TransactionContext.getTransaction(dataSource, SqlTransaction.CreatedBy.JDBC_UTIL));
    }

    // Round 15 review: a transaction that completed while its chain could not be reached (the one hiding it unregistered by its
    // own callOutsideTransaction) stayed in the chain, and the chain was then looked at one level down only: the open
    // transaction below it was dropped when the one on top completed.
    @Test
    public void testATransactionCompletedWhileItsChainIsUnreachableKeepsTheOneItHid() throws SQLException {
        final SqlTransaction bottom = unregisteredScope();
        TransactionContext.putTransaction(bottom.context());
        final SqlTransaction middle = unregisteredScope();
        TransactionContext.putTransaction(middle.context());
        final SqlTransaction top = unregisteredScope();
        TransactionContext.putTransaction(top.context());

        try {
            top.callOutsideTransaction(() -> {
                middle.commit();
                return null;
            });

            top.commit();
            assertSame(bottom.context(), TransactionContext.getTransaction(dataSource, SqlTransaction.CreatedBy.JDBC_UTIL),
                    "the open transaction below the completed one is registered again");
            bottom.commit();
        } finally {
            top.rollbackIfNotCommitted();
            middle.rollbackIfNotCommitted();
            bottom.rollbackIfNotCommitted();
        }

        assertNull(TransactionContext.getTransaction(dataSource, SqlTransaction.CreatedBy.JDBC_UTIL));
    }

    private SqlTransaction unregisteredScope() throws SQLException {
        return new TransactionContext(dataSource, connection, IsolationLevel.DEFAULT, SqlTransaction.CreatedBy.JDBC_UTIL, true, false)
                .enterScope(IsolationLevel.DEFAULT);
    }

    // Actions to run after the commit (JdbcUtil.runAfterCommit) of a JdbcUtil transaction: once its outermost scope has
    // committed, once per key; right away outside a transaction.
    @Test
    public void testActionsToRunAfterTheCommitRunOncePerKeyAfterTheOutermostCommit() throws SQLException {
        final List<String> ran = new ArrayList<>();
        final SqlTransaction outer = JdbcUtil.beginTransaction(dataSource);

        try {
            final SqlTransaction nested = JdbcUtil.beginTransaction(dataSource);
            JdbcUtil.runAfterCommit(dataSource, "key", () -> ran.add("first"));
            JdbcUtil.runAfterCommit(dataSource, "key", () -> ran.add("same key"));
            JdbcUtil.runAfterCommit(dataSource, null, () -> ran.add("no key"));
            JdbcUtil.runAfterCommit(dataSource, null, () -> ran.add("no key"));
            nested.commit();
            assertEquals(List.of(), ran, "not before the outermost commit");

            outer.commit();
            assertEquals(List.of("first", "no key", "no key"), ran);
        } finally {
            outer.rollbackIfNotCommitted();
        }

        JdbcUtil.runAfterCommit(dataSource, "key", () -> ran.add("outside"));
        assertEquals(List.of("first", "no key", "no key", "outside"), ran);
    }

    // They are discarded when the transaction rolls back: after a nested scope marked it rollback-only, and when its commit fails.
    @Test
    public void testActionsToRunAfterTheCommitAreDiscardedWhenTheTransactionRollsBack() throws SQLException {
        final List<String> ran = new ArrayList<>();
        final SqlTransaction marked = JdbcUtil.beginTransaction(dataSource);

        try {
            JdbcUtil.runAfterCommit(dataSource, null, () -> ran.add("marked"));
            JdbcUtil.beginTransaction(dataSource).rollbackIfNotCommitted();
            JdbcUtil.runAfterCommit(dataSource, null, () -> ran.add("marked"));
            assertThrows(RollbackOnlyException.class, marked::commit);
        } finally {
            marked.rollbackIfNotCommitted();
        }

        assertEquals(List.of(), ran);
    }

    // External review (P2): a commit that failed may have committed (the database committed, only its acknowledgement was
    // lost; the rollback after it undoes nothing), yet that rollback discarded the actions to run after the commit, so caches
    // kept entries the commit had made stale. They run after a failed commit too, all of them; an Error thrown by one is
    // attached to the commit's failure.
    @Test
    public void testActionsToRunAfterTheCommitRunWhenTheCommitFailed() throws SQLException {
        final List<String> ran = new ArrayList<>();
        final AssertionError actionError = new AssertionError("action failed");
        doThrow(new SQLException("commit acknowledgement lost")).when(connection).commit();
        final SqlTransaction failing = JdbcUtil.beginTransaction(dataSource);

        try {
            JdbcUtil.runAfterCommit(dataSource, null, () -> {
                throw actionError;
            });
            JdbcUtil.runAfterCommit(dataSource, null, () -> ran.add("after the failed commit"));

            final UncheckedSQLException thrown = assertThrows(UncheckedSQLException.class, failing::commit);
            assertTrue(List.of(thrown.getSuppressed()).contains(actionError), "the action's Error is attached to the commit failure");
            assertEquals(SqlTransaction.Status.ROLLED_BACK, failing.status());
        } finally {
            failing.rollbackIfNotCommitted();
        }

        assertEquals(List.of("after the failed commit"), ran);
    }

    // They run before the connection cleanup, whose failure does not keep them from running; an Error thrown by one propagates
    // once the transaction has committed and released its connection, with the cleanup failure attached.
    @Test
    public void testAnErrorFromAnActionToRunAfterTheCommitPropagatesOnceTheTransactionHasCommitted() throws SQLException {
        final List<String> ran = new ArrayList<>();
        final AssertionError actionError = new AssertionError("action failed");
        final IllegalStateException cleanupFailure = new IllegalStateException("cleanup failed");
        final SqlTransaction tran = JdbcUtil.beginTransaction(dataSource);
        doThrow(cleanupFailure).when(connection).setAutoCommit(true);

        try {
            JdbcUtil.runAfterCommit(dataSource, null, () -> {
                throw actionError;
            });
            JdbcUtil.runAfterCommit(dataSource, null, () -> ran.add("next"));

            assertSame(actionError, assertThrows(AssertionError.class, tran::commit));
            assertTrue(List.of(actionError.getSuppressed()).contains(cleanupFailure));
            assertEquals(SqlTransaction.Status.COMMITTED, tran.status());
            assertEquals(List.of("next"), ran, "a failing action does not stop the others");
            verify(connection).close();
        } finally {
            tran.rollbackIfNotCommitted();
        }
    }

    @AfterEach
    void closeMocks() throws Exception {
        if (mocks != null) {
            mocks.close();
        }
    }

    // ---- Per-scope handles: each beginTransaction call returns its own scope, so a scope's cleanup can't be mistaken for
    // another's. Before the split, the paired-cleanup markers of one shared object made these scenarios leave the
    // transaction open on the thread (registered, ACTIVE or MARKED_ROLLBACK, holding its connection). ----

    // Scenario A: a nested scope commits without a finally block, then the enclosing scope fails before its commit(). The
    // outer cleanup used to consume the nested commit's no-op marker and return, leaving the transaction open.
    @Test
    public void testOuterCleanupAfterUnpairedNestedCommitRollsBackAndReleases() throws SQLException {
        final SqlTransaction outer = JdbcUtil.beginTransaction(dataSource, IsolationLevel.READ_COMMITTED);

        try {
            final SqlTransaction inner = JdbcUtil.beginTransaction(dataSource); // a helper written without try/finally
            inner.commit();
            // the enclosing work fails here: no outer.commit()
        } finally {
            outer.rollbackIfNotCommitted();
        }

        assertEquals(SqlTransaction.Status.ROLLED_BACK, outer.status());
        assertFalse(JdbcUtil.isInTransaction(dataSource));
        verify(connection).rollback();
        verify(connection).close();
        verify(connection, never()).commit();
    }

    // Scenario B: a nested scope's work throws before its commit() and it has no finally block. The outer cleanup used to
    // exit only the nested level (marking the transaction rollback-only) and leave the outermost scope open.
    @Test
    public void testOuterCleanupExitsANestedScopeThatWasNeverCompleted() throws SQLException {
        final SqlTransaction outer = JdbcUtil.beginTransaction(dataSource, IsolationLevel.READ_COMMITTED);
        final AtomicInteger undone = new AtomicInteger();
        SqlTransaction inner = null;

        try {
            inner = JdbcUtil.beginTransaction(dataSource); // its work throws before inner.commit(); no finally
            inner.context().addRollbackAction(undone::incrementAndGet);
        } finally {
            outer.rollbackIfNotCommitted();
        }

        assertEquals(SqlTransaction.Status.ROLLED_BACK, outer.status());
        assertEquals(1, undone.get());
        assertFalse(JdbcUtil.isInTransaction(dataSource));
        verify(connection).rollback();
        verify(connection).close();

        // The leaked scope was completed by its enclosing scope: its late cleanup is a no-op.
        assertDoesNotThrow(inner::close);
        verify(connection, times(1)).rollback();
    }

    // An outermost commit() while a nested scope is still open refuses to commit: the nested scope's work may be incomplete.
    @Test
    public void testOutermostCommitWithAnOpenNestedScopeRollsBackAndThrows() throws SQLException {
        final SqlTransaction outer = JdbcUtil.beginTransaction(dataSource, IsolationLevel.READ_COMMITTED);
        final SqlTransaction inner = JdbcUtil.beginTransaction(dataSource);

        final IllegalStateException thrown = assertThrows(IllegalStateException.class, outer::commit);

        assertTrue(thrown.getMessage().contains("never completed"), thrown.getMessage());
        assertEquals(SqlTransaction.Status.ROLLED_BACK, outer.status());
        assertFalse(JdbcUtil.isInTransaction(dataSource));
        verify(connection).rollback();
        verify(connection, never()).commit();
        verify(connection).close();

        // Both cleanup calls of the finally idiom are no-ops now.
        assertDoesNotThrow(inner::rollbackIfNotCommitted);
        assertDoesNotThrow(outer::rollbackIfNotCommitted);
        verify(connection, times(1)).rollback();
    }

    // A nested commit() with a scope still open inside it refuses too, and leaves the transaction rollback-only.
    @Test
    public void testNestedCommitWithAnOpenInnerScopeMarksRollbackOnlyAndThrows() throws SQLException {
        final SqlTransaction outer = JdbcUtil.beginTransaction(dataSource, IsolationLevel.READ_COMMITTED);

        try {
            final SqlTransaction middle = JdbcUtil.beginTransaction(dataSource);
            JdbcUtil.beginTransaction(dataSource); // never completed

            assertThrows(IllegalStateException.class, middle::commit);
            assertEquals(SqlTransaction.Status.MARKED_ROLLBACK, outer.status());
            assertEquals(1, outer.context().depth());
            assertDoesNotThrow(middle::close);

            assertThrows(RollbackOnlyException.class, outer::commit); // rollback-only: rolls back and says so
        } finally {
            outer.rollbackIfNotCommitted();
        }

        assertEquals(SqlTransaction.Status.ROLLED_BACK, outer.status());
        verify(connection).rollback();
        verify(connection, never()).commit();
    }

    // Cleanup is idempotent per scope: repeated cleanup calls of a committed nested scope never exit the enclosing scope.
    @Test
    public void testRepeatedCleanupOfACommittedNestedScopeLeavesTheEnclosingScopeOpen() throws SQLException {
        final SqlTransaction outer = JdbcUtil.beginTransaction(dataSource, IsolationLevel.READ_COMMITTED);

        try {
            final SqlTransaction inner = JdbcUtil.beginTransaction(dataSource);
            inner.commit();
            inner.rollbackIfNotCommitted();
            inner.rollbackIfNotCommitted();
            inner.close();
            assertThrows(IllegalStateException.class, inner::commit); // only once per scope

            assertEquals(SqlTransaction.Status.ACTIVE, outer.status());
            assertEquals(1, outer.context().depth());

            outer.commit();
        } finally {
            outer.rollbackIfNotCommitted();
        }

        assertEquals(SqlTransaction.Status.COMMITTED, outer.status());
        verify(connection).commit();
        verify(connection, never()).rollback();
    }

    // Each beginTransaction call returns a new scope handle over the same transaction.
    @Test
    public void testNestedBeginReturnsANewScopeOfTheSameTransaction() throws SQLException {
        final SqlTransaction outer = JdbcUtil.beginTransaction(dataSource, IsolationLevel.READ_COMMITTED);

        try (outer) {
            try (SqlTransaction inner = JdbcUtil.beginTransaction(dataSource, IsolationLevel.SERIALIZABLE)) {
                assertNotSame(outer, inner);
                assertSame(outer.context(), inner.context());
                assertEquals(outer.id(), inner.id());
                assertSame(outer.connection(), inner.connection());
                assertEquals(IsolationLevel.READ_COMMITTED, outer.isolationLevel());
                assertEquals(IsolationLevel.SERIALIZABLE, inner.isolationLevel());
                assertEquals(2, outer.context().depth());
                inner.commit();
            }

            outer.commit();
        }

        verify(connection).commit();
        verify(dataSource, times(1)).getConnection();
    }

    // High #1: a nested failure that the caller catches leaves the transaction rollback-only. The outermost commit used to roll
    // back and return normally, so callInTransaction returned a result for work that was never committed.
    @Test
    public void testCallInTransactionThrowsWhenACaughtNestedFailureMadeTheTransactionRollbackOnly() throws SQLException {
        final RollbackOnlyException thrown = assertThrows(RollbackOnlyException.class, () -> JdbcUtil.callInTransaction(dataSource, () -> {
            try {
                JdbcUtil.runInTransaction(dataSource, () -> {
                    throw new SQLException("nested work failed");
                });
            } catch (final SQLException e) {
                // best-effort work: swallowed on purpose
            }

            return "done";
        }));

        assertTrue(thrown.getMessage().contains("rolled back instead of committed"), thrown.getMessage());
        assertFalse(JdbcUtil.isInTransaction(dataSource));
        verify(connection).rollback();
        verify(connection, never()).commit();
        verify(connection).close();
    }

    @Test
    public void testOutermostCommitOfARollbackOnlyTransactionRollsBackThenThrows() throws SQLException {
        final SqlTransaction outer = JdbcUtil.beginTransaction(dataSource, IsolationLevel.READ_COMMITTED);
        final AtomicInteger undone = new AtomicInteger();

        try {
            try (SqlTransaction inner = JdbcUtil.beginTransaction(dataSource)) {
                inner.context().addRollbackAction(undone::incrementAndGet);
                // no inner.commit(): the scope ends rolled back and marks the transaction rollback-only
            }

            assertEquals(SqlTransaction.Status.MARKED_ROLLBACK, outer.status());
            assertThrows(RollbackOnlyException.class, outer::commit);
        } finally {
            assertDoesNotThrow(outer::rollbackIfNotCommitted); // the paired cleanup is a no-op
        }

        assertEquals(SqlTransaction.Status.ROLLED_BACK, outer.status());
        assertEquals(1, undone.get());
        verify(connection).rollback();
        verify(connection, never()).commit();
    }

    // A scope exited by an enclosing scope that completed first (completion out of order) can't commit its work: its
    // commit() throws instead of reporting rolled-back work as committed; its cleanup call stays a no-op.
    @Test
    public void testCommitOfAScopeExitedByItsEnclosingScopeThrows() throws SQLException {
        final SqlTransaction outer = JdbcUtil.beginTransaction(dataSource, IsolationLevel.READ_COMMITTED);
        final SqlTransaction inner = JdbcUtil.beginTransaction(dataSource);

        outer.close(); // completes first: exits inner and rolls back

        assertEquals(SqlTransaction.Status.ROLLED_BACK, outer.status());
        final IllegalStateException thrown = assertThrows(IllegalStateException.class, inner::commit);
        assertTrue(thrown.getMessage().contains("has been rolled back"), thrown.getMessage());
        assertDoesNotThrow(inner::close);
        verify(connection, never()).commit();
        verify(connection, times(1)).rollback();
    }

    // The same through callInTransaction: its scope is exited when cmd completes an enclosing scope, so it throws instead of
    // returning cmd's result.
    @Test
    public void testCallInTransactionThrowsWhenCmdCompletesAnEnclosingScope() throws SQLException {
        final SqlTransaction outer = JdbcUtil.beginTransaction(dataSource, IsolationLevel.READ_COMMITTED);

        final IllegalStateException thrown = assertThrows(IllegalStateException.class, () -> JdbcUtil.callInTransaction(dataSource, () -> {
            outer.close();
            return "result";
        }));

        assertFalse(thrown instanceof RollbackOnlyException, thrown.toString());
        assertEquals(SqlTransaction.Status.ROLLED_BACK, outer.status());
        assertFalse(JdbcUtil.isInTransaction(dataSource));
        verify(connection, never()).commit();
        verify(connection, times(1)).rollback();
    }

    // External review follow-up (P3): the late commit() of a scope exited by an enclosing scope whose rollback then failed said
    // "has been rolled back", as no scope was open any more, while the status was FAILED_ROLLBACK. It follows the status now.
    @Test
    public void testCommitOfAScopeExitedByItsEnclosingScopeWhenTheRollbackFailed() throws SQLException {
        final SqlTransaction outer = JdbcUtil.beginTransaction(dataSource, IsolationLevel.READ_COMMITTED);
        final SqlTransaction inner = JdbcUtil.beginTransaction(dataSource);
        doThrow(new SQLException("connection lost")).when(connection).rollback();

        assertThrows(UncheckedSQLException.class, outer::close); // completes first: exits inner, and fails to roll back

        assertEquals(SqlTransaction.Status.FAILED_ROLLBACK, outer.status());
        assertLateCommitOfAnExitedScopeFails(inner, "could not be rolled back");
        verify(connection, never()).commit();
        verify(connection, times(1)).rollback();
    }

    // The same for a participating transaction whose rollback-only mark failed: Spring may still commit the work.
    @Test
    public void testCommitOfAScopeExitedByItsEnclosingScopeWhenTheSpringMarkFailed() throws SQLException {
        final SqlTransaction outer = participatingContext(connection).enterScope(IsolationLevel.DEFAULT);
        TransactionContext.putTransaction(outer.context());
        final IllegalStateException markFailure = new IllegalStateException("cannot mark");

        try (MockedStatic<JdbcUtil> jdbcUtil = Mockito.mockStatic(JdbcUtil.class)) {
            jdbcUtil.when(() -> JdbcUtil.markSpringTransactionRollbackOnly(dataSource)).thenThrow(markFailure);
            final SqlTransaction inner = outer.context().enterScope(IsolationLevel.DEFAULT);

            assertSame(markFailure, assertThrows(IllegalStateException.class, outer::close)); // exits inner, and fails to mark

            assertEquals(SqlTransaction.Status.FAILED_ROLLBACK, outer.status());
            assertLateCommitOfAnExitedScopeFails(inner, "could not be rolled back");
            jdbcUtil.verify(() -> JdbcUtil.markSpringTransactionRollbackOnly(dataSource), times(1));
        }

        verify(connection, never()).rollback();
        verify(connection, never()).commit();
    }

    // The late commit() describes the transaction as it stands: marked for rollback while the outermost scope is still open,
    // rolled back once it has completed.
    @Test
    public void testCommitOfAScopeExitedByANestedEnclosingScopeFollowsTheTransactionOutcome() throws SQLException {
        final SqlTransaction outer = JdbcUtil.beginTransaction(dataSource, IsolationLevel.READ_COMMITTED);
        final SqlTransaction middle = JdbcUtil.beginTransaction(dataSource);
        final SqlTransaction inner = JdbcUtil.beginTransaction(dataSource);

        middle.close(); // completes first: exits inner and marks the transaction rollback-only

        assertEquals(SqlTransaction.Status.MARKED_ROLLBACK, outer.status());
        assertLateCommitOfAnExitedScopeFails(inner, "is marked for rollback");

        assertThrows(RollbackOnlyException.class, outer::commit);

        assertEquals(SqlTransaction.Status.ROLLED_BACK, outer.status());
        assertLateCommitOfAnExitedScopeFails(inner, "has been rolled back");
        verify(connection, never()).commit();
        verify(connection, times(1)).rollback();
    }

    /**
     * Asserts that a late {@code commit()} of a scope its enclosing scope exited throws an {@link IllegalStateException} (not a
     * {@link RollbackOnlyException}) that describes the transaction as {@code outcome}, and that the scope's cleanup call is a no-op.
     */
    private static void assertLateCommitOfAnExitedScopeFails(final SqlTransaction scope, final String outcome) {
        final IllegalStateException thrown = assertThrows(IllegalStateException.class, scope::commit);

        assertFalse(thrown instanceof RollbackOnlyException, thrown.toString());
        assertTrue(thrown.getMessage().contains("an enclosing scope completed") && thrown.getMessage().endsWith("; the transaction " + outcome),
                thrown.getMessage());
        assertDoesNotThrow(scope::close);
    }

    // An outermost commit() whose leaked inner scope can't restore the isolation level (SQLException: logged) still rolls
    // back, runs the rollback actions once and throws RollbackOnlyException.
    @Test
    public void testOutermostCommitWithALeakedScopeWhoseIsolationRestoreFailsWithSQLException() throws SQLException {
        final SqlTransaction outer = JdbcUtil.beginTransaction(dataSource, IsolationLevel.READ_COMMITTED);
        final SqlTransaction inner = JdbcUtil.beginTransaction(dataSource, IsolationLevel.SERIALIZABLE); // never completed
        final AtomicInteger undone = new AtomicInteger();
        inner.context().addRollbackAction(undone::incrementAndGet);
        final AtomicBoolean rolledBack = rejectReadCommittedUntilRolledBack(new SQLException("cannot change isolation in a transaction"));

        assertThrows(RollbackOnlyException.class, outer::commit);

        assertTrue(rolledBack.get());
        assertEquals(SqlTransaction.Status.ROLLED_BACK, outer.status());
        assertEquals(1, undone.get());
        assertFalse(JdbcUtil.isInTransaction(dataSource));
        verify(connection, never()).commit();
        verify(connection).close();
    }

    // The unchecked variant: the driver failure is thrown (the leak described on it as a suppressed RollbackOnlyException),
    // but only after the outermost scope has rolled back and released the connection - nothing is left open.
    @Test
    public void testOutermostCommitWithALeakedScopeWhoseIsolationRestoreFailsUncheckedStillRollsBack() throws SQLException {
        final SqlTransaction outer = JdbcUtil.beginTransaction(dataSource, IsolationLevel.READ_COMMITTED);
        JdbcUtil.beginTransaction(dataSource, IsolationLevel.SERIALIZABLE); // never completed
        final AtomicInteger undone = new AtomicInteger();
        outer.context().addRollbackAction(undone::incrementAndGet);
        final IllegalStateException driverFailure = new IllegalStateException("connection state is unusable");
        rejectReadCommittedUntilRolledBack(driverFailure);

        final IllegalStateException thrown = assertThrows(IllegalStateException.class, outer::commit);

        assertSame(driverFailure, thrown);
        assertTrue(thrown.getSuppressed()[0] instanceof RollbackOnlyException);
        assertEquals(SqlTransaction.Status.ROLLED_BACK, outer.status());
        assertEquals(1, undone.get());
        assertFalse(JdbcUtil.isInTransaction(dataSource));
        verify(connection).rollback();
        verify(connection).close();
        assertDoesNotThrow(outer::rollbackIfNotCommitted);
    }

    // Deprecated rollback() of the outermost scope with a leaked inner scope whose isolation restore fails unchecked: the
    // rollback still happens, then the failure is thrown.
    @SuppressWarnings("deprecation")
    @Test
    public void testOutermostRollbackWithALeakedScopeWhoseIsolationRestoreFailsUncheckedStillRollsBack() throws SQLException {
        final SqlTransaction outer = JdbcUtil.beginTransaction(dataSource, IsolationLevel.READ_COMMITTED);
        final SqlTransaction inner = JdbcUtil.beginTransaction(dataSource, IsolationLevel.SERIALIZABLE); // never completed
        final IllegalStateException driverFailure = new IllegalStateException("connection state is unusable");
        rejectReadCommittedUntilRolledBack(driverFailure);

        assertSame(driverFailure, assertThrows(IllegalStateException.class, outer::rollback));

        assertEquals(SqlTransaction.Status.ROLLED_BACK, outer.status());
        assertFalse(JdbcUtil.isInTransaction(dataSource));
        verify(connection).rollback();
        verify(connection).close();
        assertDoesNotThrow(inner::close);
    }

    // Makes setTransactionIsolation(READ_COMMITTED) throw until the connection has been rolled back.
    private AtomicBoolean rejectReadCommittedUntilRolledBack(final Throwable failure) throws SQLException {
        final AtomicBoolean rolledBack = new AtomicBoolean();
        Mockito.doAnswer(invocation -> {
            rolledBack.set(true);
            return null;
        }).when(connection).rollback();
        Mockito.doAnswer(invocation -> {
            if (!rolledBack.get()) {
                throw failure;
            }
            return null;
        }).when(connection).setTransactionIsolation(Connection.TRANSACTION_READ_COMMITTED);
        return rolledBack;
    }

    // Deprecated rollback() with an open inner scope: the outermost one exits it and rolls back; a nested one exits it and
    // marks the transaction rollback-only. The exited scope's later cleanup is a no-op.
    @SuppressWarnings("deprecation")
    @Test
    public void testRollbackWithAnOpenInnerScope() throws SQLException {
        final SqlTransaction outer = JdbcUtil.beginTransaction(dataSource, IsolationLevel.READ_COMMITTED);
        final SqlTransaction middle = JdbcUtil.beginTransaction(dataSource);
        final SqlTransaction inner = JdbcUtil.beginTransaction(dataSource);

        middle.rollback();

        assertEquals(SqlTransaction.Status.MARKED_ROLLBACK, outer.status());
        assertEquals(1, outer.context().depth());
        assertDoesNotThrow(inner::close);
        verify(connection, never()).rollback();

        // The transaction is still open but rollback-only: a new scope joins it until the outermost scope completes.
        final SqlTransaction joined = JdbcUtil.beginTransaction(dataSource);
        assertSame(outer.context(), joined.context());
        joined.commit();
        assertEquals(SqlTransaction.Status.MARKED_ROLLBACK, outer.status());

        outer.rollbackIfNotCommitted();
        assertEquals(SqlTransaction.Status.ROLLED_BACK, outer.status());
        verify(connection).rollback();
    }

    // Repeated completion calls of a completed outermost scope don't touch the connection again: a repeated commit() throws
    // (only once per scope), the cleanup calls are ignored; a commit() of a scope whose commit threw RollbackOnlyException
    // throws IllegalStateException (see testCommitOfAScopeThatEndedWithoutACommitThrows).
    @SuppressWarnings("deprecation")
    @Test
    public void testRepeatedCompletionOfACompletedOutermostScopeDoesNotTouchTheConnection() throws SQLException {
        final SqlTransaction committed = JdbcUtil.beginTransaction(dataSource, IsolationLevel.READ_COMMITTED);
        committed.commit();
        assertThrows(IllegalStateException.class, committed::commit);
        assertThrows(IllegalStateException.class, committed::rollback);
        committed.close();
        verify(connection, times(1)).commit();
        verify(connection, never()).rollback();

        final SqlTransaction rollbackOnly = JdbcUtil.beginTransaction(dataSource, IsolationLevel.READ_COMMITTED);
        JdbcUtil.beginTransaction(dataSource).close(); // a nested scope ends rolled back
        assertThrows(RollbackOnlyException.class, rollbackOnly::commit);
        assertFalse(assertThrows(IllegalStateException.class, rollbackOnly::commit) instanceof RollbackOnlyException);
        assertThrows(IllegalStateException.class, rollbackOnly::rollback);
        rollbackOnly.close();
        verify(connection, times(1)).commit();
        verify(connection, times(1)).rollback();
    }

    // A commit() of a scope that ended without a commit (rolled back, or its commit failed) returned normally, as if its work had
    // been committed: the transaction had been rolled back, or was rollback-only. It throws IllegalStateException now, worded
    // after the transaction's status; a repeated commit() after a successful one throws too (testRepeatedCommitOfACommittedNestedScopeThrows).
    @SuppressWarnings("deprecation")
    @Test
    public void testCommitOfAScopeThatEndedWithoutACommitThrows() throws SQLException {
        // An outermost scope rolled back by each of its completion calls.
        for (final java.util.function.Consumer<SqlTransaction> rollback : List.<java.util.function.Consumer<SqlTransaction>> of(
                SqlTransaction::rollbackIfNotCommitted, SqlTransaction::close, SqlTransaction::rollback)) {
            final SqlTransaction tran = JdbcUtil.beginTransaction(dataSource, IsolationLevel.READ_COMMITTED);
            rollback.accept(tran);

            final IllegalStateException thrown = assertThrows(IllegalStateException.class, tran::commit);
            assertFalse(thrown instanceof RollbackOnlyException);
            assertTrue(thrown.getMessage().contains("already ended without a commit") && thrown.getMessage().contains("has been rolled back"),
                    thrown.getMessage());
            assertEquals(SqlTransaction.Status.ROLLED_BACK, tran.status());
        }

        verify(connection, never()).commit();

        // A nested scope that rolled back: the transaction is rollback-only, then rolled back by the outermost commit().
        final SqlTransaction outer = JdbcUtil.beginTransaction(dataSource, IsolationLevel.READ_COMMITTED);
        final SqlTransaction nested = JdbcUtil.beginTransaction(dataSource);
        nested.close();

        assertTrue(assertThrows(IllegalStateException.class, nested::commit).getMessage().contains("is marked for rollback"));
        assertEquals(1, outer.context().depth(), "the outermost scope is unaffected");
        assertThrows(RollbackOnlyException.class, outer::commit);
        assertTrue(assertThrows(IllegalStateException.class, nested::commit).getMessage().contains("has been rolled back"));
        verify(connection, never()).commit();
    }

    // The same for an outermost scope whose database commit failed: the automatic rollback ended the transaction.
    @Test
    public void testCommitAfterAFailedDatabaseCommitThrows() throws SQLException {
        final SqlTransaction tran = JdbcUtil.beginTransaction(dataSource, IsolationLevel.READ_COMMITTED);
        doThrow(new SQLException("commit failed")).when(connection).commit();

        assertThrows(UncheckedSQLException.class, tran::commit);
        assertEquals(SqlTransaction.Status.ROLLED_BACK, tran.status());

        assertTrue(assertThrows(IllegalStateException.class, tran::commit).getMessage().contains("has been rolled back"));
        verify(connection, times(1)).commit();
        verify(connection, times(1)).rollback();
    }

    // commit() may be called only once per scope, as in Spring (user decision): a repeated commit() of a nested scope that
    // committed was ignored, and returned normally also after the outermost scope had rolled the scope's work back. It throws
    // now, saying the transaction's status, and leaves the enclosing scope alone.
    @Test
    public void testRepeatedCommitOfACommittedNestedScopeThrows() throws SQLException {
        final SqlTransaction outer = JdbcUtil.beginTransaction(dataSource, IsolationLevel.READ_COMMITTED);
        final SqlTransaction nested = JdbcUtil.beginTransaction(dataSource);

        nested.commit();
        final IllegalStateException whileOpen = assertThrows(IllegalStateException.class, nested::commit);
        assertTrue(whileOpen.getMessage().contains("has already been committed (status: ACTIVE)"), whileOpen.getMessage());
        assertDoesNotThrow(nested::close);
        assertEquals(SqlTransaction.Status.ACTIVE, outer.status());
        assertEquals(1, outer.context().depth(), "the enclosing scope stays open");

        outer.rollbackIfNotCommitted();
        final IllegalStateException afterRollback = assertThrows(IllegalStateException.class, nested::commit);
        assertTrue(afterRollback.getMessage().contains("status: ROLLED_BACK"), afterRollback.getMessage());
        verify(connection, never()).commit();

        final SqlTransaction committedOuter = JdbcUtil.beginTransaction(dataSource, IsolationLevel.READ_COMMITTED);
        final SqlTransaction committedNested = JdbcUtil.beginTransaction(dataSource);
        committedNested.commit();
        committedOuter.commit();
        assertTrue(assertThrows(IllegalStateException.class, committedNested::commit).getMessage().contains("status: COMMITTED"));
        verify(connection, times(1)).commit();
    }

    // Transaction review, finding 8: a transaction marked rollback-only refused new scopes, so a REQUIRED DAO method,
    // callInTransaction or a batch run after a caught failure threw IllegalStateException before its work ran, while plain
    // statements on the data source kept running in the transaction. A new scope joins it now, as in a Spring transaction: its
    // work is rolled back with the transaction (its rollback actions run then), and the outermost commit() throws
    // RollbackOnlyException.
    @Test
    public void testAScopeBegunInATransactionMarkedRollbackOnlyJoinsIt() throws SQLException {
        final SqlTransaction outer = JdbcUtil.beginTransaction(dataSource, IsolationLevel.READ_COMMITTED);
        JdbcUtil.beginTransaction(dataSource).close(); // a nested scope ends rolled back
        assertEquals(SqlTransaction.Status.MARKED_ROLLBACK, outer.status());

        final AtomicInteger undone = new AtomicInteger();
        final SqlTransaction joined = JdbcUtil.beginTransaction(dataSource, IsolationLevel.SERIALIZABLE);

        assertSame(outer.context(), joined.context());
        assertEquals(2, outer.context().depth());
        verify(connection).setTransactionIsolation(Connection.TRANSACTION_SERIALIZABLE);

        joined.context().addRollbackAction(undone::incrementAndGet);
        joined.commit();
        assertEquals(SqlTransaction.Status.MARKED_ROLLBACK, outer.status());
        assertEquals(1, outer.context().depth());
        assertEquals(0, undone.get(), "the joined scope committed: its actions run when the transaction rolls back");

        assertEquals(42, JdbcUtil.callInTransaction(dataSource, () -> 42));

        assertThrows(RollbackOnlyException.class, outer::commit);
        assertEquals(1, undone.get());
        verify(connection, never()).commit();
        verify(connection).rollback();
    }

    // Transaction review, low-severity finding: after a failed rollback the connection went back to the pool with the
    // transaction's work pending, and a pool that restores auto-commit on its connections committed it. An owned connection is
    // aborted now before it is released, without restoring its state; an abort failure is logged.
    @Test
    public void testAFailedRollbackAbortsTheConnectionBeforeReleasingIt() throws SQLException {
        for (final boolean abortFails : new boolean[] { false, true }) {
            Mockito.reset(connection);
            when(connection.getAutoCommit()).thenReturn(true);
            when(connection.getTransactionIsolation()).thenReturn(Connection.TRANSACTION_READ_COMMITTED);
            doThrow(new SQLException("rollback failed")).when(connection).rollback();

            if (abortFails) {
                doThrow(new java.sql.SQLFeatureNotSupportedException("abort")).when(connection).abort(ArgumentMatchers.any());
            }

            final SqlTransaction tran = JdbcUtil.beginTransaction(dataSource, IsolationLevel.READ_COMMITTED);
            final UncheckedSQLException thrown = assertThrows(UncheckedSQLException.class, tran::rollbackIfNotCommitted);

            assertEquals("rollback failed", thrown.getCause().getMessage());
            assertEquals(SqlTransaction.Status.FAILED_ROLLBACK, tran.status());

            final InOrder inOrder = inOrder(connection);
            inOrder.verify(connection).rollback();
            inOrder.verify(connection).abort(ArgumentMatchers.any());
            inOrder.verify(connection).close();
            verify(connection, never()).setAutoCommit(true);
        }
    }

    // Round 14 review: an Error thrown by Connection.abort (a driver written before JDBC 4.1 throws AbstractMethodError, as jTDS
    // does) skipped the release: the connection was never closed, and kept the transaction's work and its locks. It is released
    // all the same now; a LinkageError from the abort is logged, as an unsupported abort is, and another Error is attached to the
    // rollback failure.
    @Test
    public void testAFailedRollbackReleasesTheConnectionWhenItsAbortThrowsAnError() throws SQLException {
        for (final Error abortError : new Error[] { new AbstractMethodError("abort"), new InternalError("abort") }) {
            Mockito.reset(connection);
            when(connection.getAutoCommit()).thenReturn(true);
            when(connection.getTransactionIsolation()).thenReturn(Connection.TRANSACTION_READ_COMMITTED);
            doThrow(new SQLException("rollback failed")).when(connection).rollback();
            doThrow(abortError).when(connection).abort(ArgumentMatchers.any());

            final SqlTransaction tran = JdbcUtil.beginTransaction(dataSource, IsolationLevel.READ_COMMITTED);
            final UncheckedSQLException thrown = assertThrows(UncheckedSQLException.class, tran::rollbackIfNotCommitted);

            assertEquals(SqlTransaction.Status.FAILED_ROLLBACK, tran.status());
            verify(connection).close();
            assertEquals(!(abortError instanceof LinkageError), List.of(thrown.getSuppressed()).contains(abortError));
            assertNull(TransactionContext.getTransaction(dataSource, SqlTransaction.CreatedBy.JDBC_UTIL));
        }
    }

    // The same after a failed commit whose automatic rollback fails too; a participating transaction whose Spring mark fails
    // does not touch Spring's connection.
    @Test
    public void testAFailedRollbackAfterAFailedCommitAbortsTheConnection() throws SQLException {
        final SqlTransaction tran = JdbcUtil.beginTransaction(dataSource, IsolationLevel.READ_COMMITTED);
        doThrow(new SQLException("commit failed")).when(connection).commit();
        doThrow(new SQLException("rollback failed")).when(connection).rollback();

        assertThrows(UncheckedSQLException.class, tran::commit);
        assertEquals(SqlTransaction.Status.FAILED_ROLLBACK, tran.status());
        verify(connection).abort(ArgumentMatchers.any());
        verify(connection).close();

        final Connection springConnection = Mockito.mock(Connection.class);
        final SqlTransaction participating = participatingContext(springConnection).enterScope(IsolationLevel.DEFAULT);
        TransactionContext.putTransaction(participating.context());

        try (MockedStatic<JdbcUtil> jdbcUtil = Mockito.mockStatic(JdbcUtil.class)) {
            jdbcUtil.when(() -> JdbcUtil.markSpringTransactionRollbackOnly(dataSource)).thenThrow(new IllegalStateException("mark failed"));

            assertThrows(IllegalStateException.class, participating::rollbackIfNotCommitted);
            assertEquals(SqlTransaction.Status.FAILED_ROLLBACK, participating.status());
            jdbcUtil.verify(() -> JdbcUtil.releaseSpringManagedConnection(springConnection, dataSource));
        }

        verify(springConnection, never()).abort(ArgumentMatchers.any());
        verify(springConnection, never()).close();
    }

    // A nested commit() that exits a leaked scope and then can't restore the enclosing isolation level: the scope stays open
    // for its cleanup call, the transaction is marked rollback-only, and the failure says why.
    @Test
    public void testNestedCommitThatExitedALeakedScopeAndThenFailsToExitStaysOpenForCleanup() throws SQLException {
        final SqlTransaction outer = JdbcUtil.beginTransaction(dataSource, IsolationLevel.READ_COMMITTED);
        final SqlTransaction middle = JdbcUtil.beginTransaction(dataSource, IsolationLevel.SERIALIZABLE);
        JdbcUtil.beginTransaction(dataSource, IsolationLevel.REPEATABLE_READ); // never completed
        doThrow(new SQLException("restore failed")).doNothing().when(connection).setTransactionIsolation(Connection.TRANSACTION_READ_COMMITTED);

        try {
            final UncheckedSQLException thrown = assertThrows(UncheckedSQLException.class, middle::commit);
            assertTrue(thrown.getSuppressed().length > 0 && thrown.getSuppressed()[0].getMessage().contains("never completed"));
            assertEquals(SqlTransaction.Status.MARKED_ROLLBACK, outer.status());
            assertEquals(2, outer.context().depth());
        } finally {
            middle.rollbackIfNotCommitted(); // the retry exits the scope
        }

        assertEquals(1, outer.context().depth());
        assertThrows(RollbackOnlyException.class, outer::commit);
        verify(connection, never()).commit();
    }

    // Each handle keeps its own settings; the transaction's effective ones follow the innermost open scope.
    @Test
    public void testScopeSettingsAndEffectiveSettings() throws SQLException {
        final SqlTransaction outer = JdbcUtil.beginTransaction(dataSource, IsolationLevel.READ_COMMITTED);

        try (outer) {
            try (SqlTransaction inner = JdbcUtil.beginTransaction(dataSource, IsolationLevel.SERIALIZABLE)) {
                assertEquals(IsolationLevel.READ_COMMITTED, outer.isolationLevel());
                assertEquals(IsolationLevel.SERIALIZABLE, inner.isolationLevel());
                assertEquals(IsolationLevel.SERIALIZABLE, outer.context().isolationLevel());
                inner.commit();
            }

            assertEquals(IsolationLevel.READ_COMMITTED, outer.context().isolationLevel());
            outer.commit();
        }
    }

    // The owner-thread check comes first, also for a completed scope.
    @Test
    public void testCompletedScopeStillRejectsOtherThreads() throws Exception {
        final SqlTransaction transaction = JdbcUtil.beginTransaction(dataSource, IsolationLevel.READ_COMMITTED);
        transaction.commit();

        for (final Runnable operation : new Runnable[] { transaction::commit, transaction::close, transaction::rollbackIfNotCommitted }) {
            final AtomicReference<Throwable> failure = new AtomicReference<>();
            final Thread thread = new Thread(() -> {
                try {
                    operation.run();
                } catch (final Throwable e) {
                    failure.set(e);
                }
            });
            thread.start();
            thread.join();
            assertTrue(failure.get() instanceof IllegalStateException, String.valueOf(failure.get()));
        }
    }

    // callOutsideTransaction with an open nested scope: the transaction is registered again afterwards, the scopes are intact,
    // and the nested scope commits normally.
    @Test
    public void testCallOutsideTransactionKeepsOpenNestedScopes() throws Exception {
        final SqlTransaction outer = JdbcUtil.beginTransaction(dataSource, IsolationLevel.READ_COMMITTED);
        final SqlTransaction inner = JdbcUtil.beginTransaction(dataSource);

        assertEquals("outside", inner.callOutsideTransaction(() -> {
            assertFalse(JdbcUtil.isInTransaction(dataSource));
            return "outside";
        }));

        assertSame(outer.context(), TransactionContext.getTransaction(dataSource, SqlTransaction.CreatedBy.JDBC_UTIL));
        assertEquals(2, outer.context().depth());
        inner.commit();
        outer.commit();
        verify(connection).commit();
    }

    // A participating transaction with a leaked nested scope: the outermost commit() marks the Spring transaction
    // rollback-only, runs the rollback actions recorded without Spring's synchronization once it is marked, and throws
    // RollbackOnlyException.
    @Test
    public void testParticipatingCommitWithALeakedScopeMarksTheSpringTransactionAndThrows() throws SQLException {
        final SqlTransaction outer = participatingContext(connection).enterScope(IsolationLevel.DEFAULT);
        TransactionContext.putTransaction(outer.context());
        final AtomicInteger undone = new AtomicInteger();

        try (MockedStatic<JdbcUtil> jdbcUtil = Mockito.mockStatic(JdbcUtil.class)) {
            jdbcUtil.when(() -> JdbcUtil.registerSpringRollbackAction(ArgumentMatchers.same(dataSource), ArgumentMatchers.any())).thenReturn(false);
            final SqlTransaction inner = outer.context().enterScope(IsolationLevel.DEFAULT); // never completed
            inner.context().addRollbackAction(undone::incrementAndGet);

            final RollbackOnlyException thrown = assertThrows(RollbackOnlyException.class, outer::commit);

            assertTrue(thrown.getMessage().contains("never completed") && thrown.getMessage().contains("will be rolled back by the Spring-managed transaction")
                    && !thrown.getMessage().contains("has been rolled back"), thrown.getMessage());
            assertEquals(SqlTransaction.Status.ROLLED_BACK, outer.status());
            assertEquals(1, undone.get());
            assertNull(TransactionContext.getTransaction(dataSource, SqlTransaction.CreatedBy.JDBC_UTIL));
            jdbcUtil.verify(() -> JdbcUtil.markSpringTransactionRollbackOnly(dataSource));
            jdbcUtil.verify(() -> JdbcUtil.releaseSpringManagedConnection(connection, dataSource));
        }

        verify(connection, never()).rollback();
        verify(connection, never()).commit();
    }

    // External review (P1, P2): ... and when the Spring transaction can't be marked. The mark failure propagates itself: it
    // used to be attached to a RollbackOnlyException, which reports the transaction as rolled back although its status is
    // FAILED_ROLLBACK (an Error too, e.g. Spring's classes failing to link). The leak is described on it without claiming a
    // rollback. The rollback actions recorded without Spring's synchronization are discarded: they used to run as soon as the
    // leak was detected, before the mark was attempted, although Spring may still commit the work.
    @Test
    public void testParticipatingCommitWithALeakedScopeWhenTheSpringMarkFails() throws SQLException {
        final IllegalStateException markFailure = new IllegalStateException("cannot mark");
        final Throwable thrown = completeParticipatingTransactionWithALeakedScopeWhenTheSpringMarkFails(markFailure, SqlTransaction::commit);

        assertSame(markFailure, thrown);
        assertLeakDescribedWithoutClaimingARollback(thrown);
    }

    @Test
    public void testParticipatingCommitWithALeakedScopeWhenTheSpringMarkFailsWithAnError() throws SQLException {
        final NoClassDefFoundError markFailure = new NoClassDefFoundError("org/springframework/transaction/support/TransactionSynchronizationManager");
        final Throwable thrown = completeParticipatingTransactionWithALeakedScopeWhenTheSpringMarkFails(markFailure, SqlTransaction::commit);

        assertSame(markFailure, thrown);
        assertLeakDescribedWithoutClaimingARollback(thrown);
    }

    @SuppressWarnings("deprecation")
    @Test
    public void testParticipatingRollbackWithALeakedScopeWhenTheSpringMarkFails() throws SQLException {
        final IllegalStateException markFailure = new IllegalStateException("cannot mark");

        assertSame(markFailure, completeParticipatingTransactionWithALeakedScopeWhenTheSpringMarkFails(markFailure, SqlTransaction::rollback));
    }

    @Test
    public void testParticipatingCloseWithALeakedScopeWhenTheSpringMarkFails() throws SQLException {
        final IllegalStateException markFailure = new IllegalStateException("cannot mark");

        assertSame(markFailure, completeParticipatingTransactionWithALeakedScopeWhenTheSpringMarkFails(markFailure, SqlTransaction::close));
    }

    /**
     * Completes the outermost scope of a participating transaction whose nested scope (with a rollback action recorded without
     * Spring's synchronization) was never completed, while marking the Spring transaction fails with {@code markFailure}.
     *
     * @return what completing the outermost scope threw
     */
    private Throwable completeParticipatingTransactionWithALeakedScopeWhenTheSpringMarkFails(final Throwable markFailure,
            final java.util.function.Consumer<SqlTransaction> outermostEnd) throws SQLException {
        final SqlTransaction outer = participatingContext(connection).enterScope(IsolationLevel.DEFAULT);
        TransactionContext.putTransaction(outer.context());
        final AtomicInteger undone = new AtomicInteger();
        final Throwable thrown;

        try (MockedStatic<JdbcUtil> jdbcUtil = Mockito.mockStatic(JdbcUtil.class)) {
            jdbcUtil.when(() -> JdbcUtil.registerSpringRollbackAction(ArgumentMatchers.same(dataSource), ArgumentMatchers.any())).thenReturn(false);
            jdbcUtil.when(() -> JdbcUtil.markSpringTransactionRollbackOnly(dataSource)).thenThrow(markFailure);
            final SqlTransaction inner = outer.context().enterScope(IsolationLevel.DEFAULT); // never completed
            inner.context().addRollbackAction(undone::incrementAndGet);

            thrown = assertThrows(Throwable.class, () -> outermostEnd.accept(outer));

            assertEquals(SqlTransaction.Status.FAILED_ROLLBACK, outer.status());
            assertEquals(0, undone.get(), "the in-memory changes are left to the Spring transaction's outcome");
            assertNull(TransactionContext.getTransaction(dataSource, SqlTransaction.CreatedBy.JDBC_UTIL));
            jdbcUtil.verify(() -> JdbcUtil.releaseSpringManagedConnection(connection, dataSource));

            // The paired cleanup calls: no second mark, nothing run.
            assertDoesNotThrow(inner::close);
            assertDoesNotThrow(outer::close);
            assertEquals(0, undone.get());
            jdbcUtil.verify(() -> JdbcUtil.markSpringTransactionRollbackOnly(dataSource), times(1));
            jdbcUtil.verify(() -> JdbcUtil.releaseSpringManagedConnection(connection, dataSource), times(1));
        }

        verify(connection, never()).rollback();
        verify(connection, never()).commit();

        return thrown;
    }

    // External review (P2): an outermost commit() with a leaked scope whose rollback fails used to throw a RollbackOnlyException
    // ("has been rolled back") with the rollback failure attached, while the status was FAILED_ROLLBACK. The rollback failure
    // is thrown now, as for a transaction a nested scope marked rollback-only, with the leak described on it.
    @Test
    public void testOutermostCommitWithALeakedScopeWhenTheRollbackFailsThrowsTheRollbackFailure() throws SQLException {
        final SqlTransaction outer = JdbcUtil.beginTransaction(dataSource, IsolationLevel.READ_COMMITTED);
        final SqlTransaction inner = JdbcUtil.beginTransaction(dataSource); // never completed
        final AtomicInteger undone = new AtomicInteger();
        inner.context().addRollbackAction(undone::incrementAndGet);
        final SQLException rollbackFailure = new SQLException("connection lost");
        doThrow(rollbackFailure).when(connection).rollback();

        final UncheckedSQLException thrown = assertThrows(UncheckedSQLException.class, outer::commit);

        assertSame(rollbackFailure, thrown.getCause());
        assertLeakDescribedWithoutClaimingARollback(thrown);
        assertEquals(SqlTransaction.Status.FAILED_ROLLBACK, outer.status());
        assertEquals(1, undone.get(), "the work was never committed: its in-memory changes are undone either way");
        assertFalse(JdbcUtil.isInTransaction(dataSource));
        verify(connection, never()).commit();
        verify(connection, never()).setAutoCommit(true); // restoring auto-commit would commit the pending work
        verify(connection).close();

        assertDoesNotThrow(inner::close);
        assertDoesNotThrow(outer::rollbackIfNotCommitted);
        verify(connection, times(1)).rollback();
    }

    // With both an unchecked failure while exiting the leaked scope and a failed rollback, the former is thrown (as by
    // rollback()), with the rollback failure and the leak, described without claiming a rollback, attached.
    @Test
    public void testOutermostCommitWithALeakedScopeWhoseExitAndRollbackBothFail() throws SQLException {
        final SqlTransaction outer = JdbcUtil.beginTransaction(dataSource, IsolationLevel.READ_COMMITTED);
        JdbcUtil.beginTransaction(dataSource, IsolationLevel.SERIALIZABLE); // never completed
        final IllegalStateException driverFailure = new IllegalStateException("connection state is unusable");
        doThrow(driverFailure).when(connection).setTransactionIsolation(Connection.TRANSACTION_READ_COMMITTED);
        final SQLException rollbackFailure = new SQLException("connection lost");
        doThrow(rollbackFailure).when(connection).rollback();

        final IllegalStateException thrown = assertThrows(IllegalStateException.class, outer::commit);

        assertSame(driverFailure, thrown);
        final Throwable[] suppressed = thrown.getSuppressed();
        assertEquals(2, suppressed.length, java.util.Arrays.toString(suppressed));
        assertTrue(suppressed[0] instanceof UncheckedSQLException && suppressed[0].getCause() == rollbackFailure, suppressed[0].toString());
        assertFalse(suppressed[1] instanceof RollbackOnlyException, suppressed[1].toString());
        assertTrue(suppressed[1].getMessage().contains("never completed") && suppressed[1].getMessage().contains("could not be rolled back"),
                suppressed[1].getMessage());
        assertEquals(SqlTransaction.Status.FAILED_ROLLBACK, outer.status());
        assertFalse(JdbcUtil.isInTransaction(dataSource));
        verify(connection, never()).commit();
        verify(connection).close();
    }

    // External review follow-up: a connection cleanup that fails after the rollback of an outermost commit() with a leaked scope
    // is thrown, as a failed rollback is, with the leak attached. The rollback itself succeeded, so the leak is a
    // RollbackOnlyException: the description follows the recorded outcome, not whether completing the transaction threw.
    @Test
    public void testOutermostCommitWithALeakedScopeWhenTheCleanupFailsAfterTheRollback() throws SQLException {
        final SqlTransaction outer = JdbcUtil.beginTransaction(dataSource, IsolationLevel.READ_COMMITTED);
        final SqlTransaction inner = JdbcUtil.beginTransaction(dataSource); // never completed
        final AtomicInteger undone = new AtomicInteger();
        inner.context().addRollbackAction(undone::incrementAndGet);
        final IllegalStateException cleanupFailure = new IllegalStateException("connection broken");
        doThrow(cleanupFailure).when(connection).setAutoCommit(true);

        final IllegalStateException thrown = assertThrows(IllegalStateException.class, outer::commit);

        assertSame(cleanupFailure, thrown);
        assertReasonAttached(thrown, "never completed", true);
        assertEquals(SqlTransaction.Status.ROLLED_BACK, outer.status());
        assertEquals(1, undone.get());
        assertFalse(JdbcUtil.isInTransaction(dataSource));
        verify(connection).rollback();
        verify(connection, never()).commit();
        verify(connection).close();

        assertDoesNotThrow(inner::close);
        assertDoesNotThrow(outer::rollbackIfNotCommitted);
        verify(connection, times(1)).rollback();
    }

    // The same for a participating transaction: the Spring transaction was marked, so the rollback actions ran, and releasing
    // the connection to Spring then failed.
    @Test
    public void testParticipatingCommitWithALeakedScopeWhenReleasingTheConnectionFailsAfterTheMark() throws SQLException {
        final SqlTransaction outer = participatingContext(connection).enterScope(IsolationLevel.DEFAULT);
        TransactionContext.putTransaction(outer.context());
        final AtomicInteger undone = new AtomicInteger();
        final IllegalStateException releaseFailure = new IllegalStateException("release failed");

        try (MockedStatic<JdbcUtil> jdbcUtil = Mockito.mockStatic(JdbcUtil.class)) {
            jdbcUtil.when(() -> JdbcUtil.registerSpringRollbackAction(ArgumentMatchers.same(dataSource), ArgumentMatchers.any())).thenReturn(false);
            jdbcUtil.when(() -> JdbcUtil.releaseSpringManagedConnection(connection, dataSource)).thenThrow(releaseFailure);
            final SqlTransaction inner = outer.context().enterScope(IsolationLevel.DEFAULT); // never completed
            inner.context().addRollbackAction(undone::incrementAndGet);

            final IllegalStateException thrown = assertThrows(IllegalStateException.class, outer::commit);

            assertSame(releaseFailure, thrown);
            assertReasonAttached(thrown, "never completed", true);
            assertTrue(thrown.getSuppressed()[0].getMessage().contains("will be rolled back by the Spring-managed transaction"),
                    thrown.getSuppressed()[0].getMessage());
            assertEquals(SqlTransaction.Status.ROLLED_BACK, outer.status());
            assertEquals(1, undone.get(), "the Spring transaction can only end rolled back");
            assertNull(TransactionContext.getTransaction(dataSource, SqlTransaction.CreatedBy.JDBC_UTIL));
            jdbcUtil.verify(() -> JdbcUtil.markSpringTransactionRollbackOnly(dataSource), times(1));

            assertDoesNotThrow(inner::close);
            assertDoesNotThrow(outer::close);
            assertEquals(1, undone.get());
            jdbcUtil.verify(() -> JdbcUtil.markSpringTransactionRollbackOnly(dataSource), times(1));
            jdbcUtil.verify(() -> JdbcUtil.releaseSpringManagedConnection(connection, dataSource), times(1));
        }

        verify(connection, never()).rollback();
        verify(connection, never()).commit();
    }

    // External review follow-up: the outermost commit() of a transaction a nested scope marked rollback-only throws the failure of
    // its rollback, as before, but now with the reason the commit failed attached (as for a leaked scope), worded for a
    // rollback that failed. The reason used to be missing.
    @Test
    public void testOutermostCommitOfARollbackOnlyTransactionWhenTheRollbackFails() throws SQLException {
        final SqlTransaction outer = JdbcUtil.beginTransaction(dataSource, IsolationLevel.READ_COMMITTED);
        JdbcUtil.beginTransaction(dataSource).rollbackIfNotCommitted(); // marks the transaction rollback-only
        final SQLException rollbackFailure = new SQLException("connection lost");
        doThrow(rollbackFailure).when(connection).rollback();

        final UncheckedSQLException thrown = assertThrows(UncheckedSQLException.class, outer::commit);

        assertSame(rollbackFailure, thrown.getCause());
        assertReasonAttached(thrown, "marked it rollback-only", false);
        assertEquals(SqlTransaction.Status.FAILED_ROLLBACK, outer.status());
        assertFalse(JdbcUtil.isInTransaction(dataSource));
        verify(connection, never()).commit();
        verify(connection, never()).setAutoCommit(true); // restoring auto-commit would commit the pending work
        verify(connection).close();
        assertDoesNotThrow(outer::rollbackIfNotCommitted);
    }

    // A connection cleanup that fails after the successful rollback of such a commit() is thrown with a RollbackOnlyException
    // attached: it used to be thrown alone, without saying that the transaction had been rolled back instead of committed.
    @Test
    public void testOutermostCommitOfARollbackOnlyTransactionWhenTheCleanupFailsAfterTheRollback() throws SQLException {
        final SqlTransaction outer = JdbcUtil.beginTransaction(dataSource, IsolationLevel.READ_COMMITTED);
        JdbcUtil.beginTransaction(dataSource).rollbackIfNotCommitted(); // marks the transaction rollback-only
        final IllegalStateException cleanupFailure = new IllegalStateException("connection broken");
        doThrow(cleanupFailure).when(connection).setAutoCommit(true);

        final IllegalStateException thrown = assertThrows(IllegalStateException.class, outer::commit);

        assertSame(cleanupFailure, thrown);
        assertReasonAttached(thrown, "marked it rollback-only", true);
        assertTrue(thrown.getSuppressed()[0].getMessage().contains("rolled back instead of committed"), thrown.getSuppressed()[0].getMessage());
        assertEquals(SqlTransaction.Status.ROLLED_BACK, outer.status());
        assertFalse(JdbcUtil.isInTransaction(dataSource));
        verify(connection).rollback();
        verify(connection, never()).commit();
        verify(connection).close();
        assertDoesNotThrow(outer::rollbackIfNotCommitted);
    }

    // An Error thrown by a rollback action (an OutOfMemoryError or a LinkageError while putting generated IDs back) was logged
    // like an exception when nothing else failed, so it went unnoticed. It propagates now, once the scope has completed: the
    // other actions still run, the transaction is rolled back and its connection released. An exception is still only logged.
    @Test
    public void testErrorOfARollbackActionPropagatesFromTheOutermostRollback() throws SQLException {
        final SqlTransaction tran = JdbcUtil.beginTransaction(dataSource, IsolationLevel.READ_COMMITTED);
        final AtomicInteger undone = new AtomicInteger();
        final RollbackActionError older = new RollbackActionError("older");
        final RollbackActionError newer = new RollbackActionError("newer");

        // Run newest first: newer, the count, the exception (logged only), older (attached to newer), the count.
        tran.context().addRollbackAction(undone::incrementAndGet);
        tran.context().addRollbackAction(() -> {
            throw older;
        });
        tran.context().addRollbackAction(() -> {
            throw new IllegalStateException("logged only");
        });
        tran.context().addRollbackAction(undone::incrementAndGet);
        tran.context().addRollbackAction(() -> {
            throw newer;
        });

        assertSame(newer, assertThrows(RollbackActionError.class, tran::rollbackIfNotCommitted));
        assertArrayEquals(new Throwable[] { older }, newer.getSuppressed());
        assertEquals(2, undone.get());
        assertEquals(SqlTransaction.Status.ROLLED_BACK, tran.status());
        assertNull(TransactionContext.getTransaction(dataSource, SqlTransaction.CreatedBy.JDBC_UTIL));
        assertDoesNotThrow(tran::close);
        verify(connection).rollback();
        verify(connection).close();
    }

    // The same for a nested scope that rolls back: its actions run once the transaction is marked rollback-only, and an Error
    // of one propagates after the scope has been exited.
    @SuppressWarnings("deprecation")
    @Test
    public void testErrorOfARollbackActionPropagatesFromANestedRollbackOnceTheScopeHasExited() throws SQLException {
        for (final boolean deprecatedRollback : new boolean[] { false, true }) {
            final SqlTransaction outer = JdbcUtil.beginTransaction(dataSource, IsolationLevel.READ_COMMITTED);
            final SqlTransaction inner = JdbcUtil.beginTransaction(dataSource);
            final RollbackActionError error = new RollbackActionError("nested");
            inner.context().addRollbackAction(() -> {
                throw error;
            });

            assertSame(error, assertThrows(RollbackActionError.class, deprecatedRollback ? inner::rollback : inner::close));

            assertEquals(SqlTransaction.Status.MARKED_ROLLBACK, outer.status());
            assertEquals(1, outer.context().depth(), "the nested scope has exited");
            assertDoesNotThrow(inner::close);
            assertThrows(RollbackOnlyException.class, outer::commit);
            assertNull(TransactionContext.getTransaction(dataSource, SqlTransaction.CreatedBy.JDBC_UTIL));
        }

        verify(connection, times(2)).rollback();
        verify(connection, never()).commit();
        verify(connection, times(2)).close();
    }

    // The same for a scope that was never completed: the enclosing commit() exits it and runs the actions, then rolls back and
    // throws the action's Error, with the reason the commit failed attached.
    @Test
    public void testErrorOfARollbackActionPropagatesFromTheCommitThatExitsALeakedScope() throws SQLException {
        final SqlTransaction outer = JdbcUtil.beginTransaction(dataSource, IsolationLevel.READ_COMMITTED);
        final SqlTransaction inner = JdbcUtil.beginTransaction(dataSource); // never completed
        final RollbackActionError error = new RollbackActionError("leaked");
        inner.context().addRollbackAction(() -> {
            throw error;
        });

        assertSame(error, assertThrows(RollbackActionError.class, outer::commit));

        assertReasonAttached(error, "never completed", true);
        assertEquals(SqlTransaction.Status.ROLLED_BACK, outer.status());
        assertNull(TransactionContext.getTransaction(dataSource, SqlTransaction.CreatedBy.JDBC_UTIL));
        verify(connection).rollback();
        verify(connection, never()).commit();
        verify(connection).close();
    }

    // The same for a participating transaction without Spring's transaction synchronization, which runs the actions it recorded
    // once its outermost scope has marked the Spring transaction rollback-only.
    @Test
    public void testErrorOfARollbackActionPropagatesFromAParticipatingRollbackOnceTheSpringTransactionIsMarked() throws SQLException {
        final SqlTransaction outer = participatingContext(connection).enterScope(IsolationLevel.DEFAULT);
        TransactionContext.putTransaction(outer.context());
        final RollbackActionError error = new RollbackActionError("participating");

        try (MockedStatic<JdbcUtil> jdbcUtil = Mockito.mockStatic(JdbcUtil.class)) {
            jdbcUtil.when(() -> JdbcUtil.registerSpringRollbackAction(ArgumentMatchers.same(dataSource), ArgumentMatchers.any())).thenReturn(false);
            outer.context().addRollbackAction(() -> {
                throw error;
            });

            assertSame(error, assertThrows(RollbackActionError.class, outer::rollbackIfNotCommitted));

            assertEquals(SqlTransaction.Status.ROLLED_BACK, outer.status());
            assertNull(TransactionContext.getTransaction(dataSource, SqlTransaction.CreatedBy.JDBC_UTIL));
            jdbcUtil.verify(() -> JdbcUtil.markSpringTransactionRollbackOnly(dataSource));
            jdbcUtil.verify(() -> JdbcUtil.releaseSpringManagedConnection(connection, dataSource));
        }

        verify(connection, never()).rollback();
    }

    /** An {@link Error} thrown by a rollback action. */
    private static final class RollbackActionError extends Error {
        private static final long serialVersionUID = 1L;

        RollbackActionError(final String message) {
            super(message);
        }
    }

    // The leak is attached to the failure thrown instead of a RollbackOnlyException, worded for a rollback that failed.
    private static void assertLeakDescribedWithoutClaimingARollback(final Throwable thrown) {
        assertReasonAttached(thrown, "never completed", false);
    }

    /**
     * Asserts that the reason a commit failed is attached to the failure thrown instead (alone), worded after the outcome of the
     * rollback: a {@link RollbackOnlyException} once rolled back, otherwise an {@link IllegalStateException} that claims no rollback.
     */
    private static void assertReasonAttached(final Throwable thrown, final String reason, final boolean rolledBack) {
        final Throwable[] suppressed = thrown.getSuppressed();

        assertEquals(1, suppressed.length, java.util.Arrays.toString(suppressed));
        final String message = suppressed[0].getMessage();
        assertTrue(message.contains(reason), message);

        if (rolledBack) {
            assertTrue(suppressed[0] instanceof RollbackOnlyException, suppressed[0].toString());
            // A participating transaction's rollback marks the Spring transaction, which rolls back the work when it completes.
            assertTrue(message.contains("has been rolled back") || message.contains("will be rolled back by the Spring-managed transaction"), message);
        } else {
            assertTrue(suppressed[0] instanceof IllegalStateException && !(suppressed[0] instanceof RollbackOnlyException), suppressed[0].toString());
            assertTrue(message.contains("could not be rolled back"), message);
            assertFalse(message.contains("has been rolled back"), message);
        }
    }
}
