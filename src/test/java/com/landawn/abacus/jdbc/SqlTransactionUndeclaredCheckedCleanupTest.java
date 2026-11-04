package com.landawn.abacus.jdbc;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.io.IOException;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executor;

import javax.sql.DataSource;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.Mock;
import org.mockito.MockedStatic;
import org.mockito.Mockito;
import org.mockito.MockitoAnnotations;

import com.landawn.abacus.TestBase;
import com.landawn.abacus.exception.UncheckedSQLException;

/**
 * Transaction completion paths facing a checked exception that a driver or wrapper throws without declaring it (Kotlin, a
 * sneaky throw).
 */
public class SqlTransactionUndeclaredCheckedCleanupTest extends TestBase {

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

    @AfterEach
    public void tearDown() throws Exception {
        Thread.interrupted(); // a test that checks the interrupt flag must not leave it set for the next one
        mocks.close();
    }

    @SuppressWarnings("unchecked")
    private static <E extends Throwable> void sneakyThrow(final Throwable e) throws E {
        throw (E) e;
    }

    // ---- rollback actions (an ID setter written in Kotlin, through @AccessFieldByMethod, or a sneaky throw)

    // The rollback actions ran in executeRollback's finally block, through a catch of RuntimeException | Error only: an
    // undeclared checked exception of one escaped there, skipping the older actions and the connection cleanup (the
    // connection was never reset nor released). Like any other exception of an action after a successful rollback, it is
    // now logged only.
    @Test
    void undeclaredCheckedRollbackActionFailureIsLoggedAndTheOtherActionsAndTheCleanupStillRun() throws Exception {
        final List<String> ran = new ArrayList<>();
        final SqlTransaction transaction = JdbcUtil.beginTransaction(dataSource, IsolationLevel.READ_COMMITTED);
        transaction.context().addRollbackAction(() -> ran.add("older"));
        transaction.context().addRollbackAction(() -> sneakyThrow(new IOException("ID setter failed")));

        assertDoesNotThrow(transaction::rollbackIfNotCommitted);

        assertEquals(List.of("older"), ran);
        assertEquals(SqlTransaction.Status.ROLLED_BACK, transaction.status());
        verify(connection).rollback();
        verify(connection).setAutoCommit(true);
        verify(connection).close();
        assertFalse(JdbcUtil.isInTransaction(dataSource));
    }

    // After a failed ROLLBACK, the action's undeclared checked exception escaped the finally block in place of the rollback's
    // failure, and the connection was neither aborted nor released.
    @Test
    void undeclaredCheckedRollbackActionFailureIsAttachedToAFailedRollback() throws Exception {
        final List<String> ran = new ArrayList<>();
        final IOException actionFailure = new IOException("ID setter failed");
        final SQLException rollbackFailure = new SQLException("rollback failed");
        final SqlTransaction transaction = JdbcUtil.beginTransaction(dataSource, IsolationLevel.READ_COMMITTED);
        transaction.context().addRollbackAction(() -> ran.add("older"));
        transaction.context().addRollbackAction(() -> sneakyThrow(actionFailure));
        doThrow(rollbackFailure).when(connection).rollback();

        final UncheckedSQLException thrown = assertThrows(UncheckedSQLException.class, transaction::rollbackIfNotCommitted);

        assertSame(rollbackFailure, thrown.getCause());
        assertArrayEquals(new Throwable[] { actionFailure }, thrown.getSuppressed());
        assertEquals(List.of("older"), ran);
        assertEquals(SqlTransaction.Status.FAILED_ROLLBACK, transaction.status());
        verify(connection).abort(any(Executor.class));
        verify(connection).close();
        assertFalse(JdbcUtil.isInTransaction(dataSource));
    }

    // A nested scope's rollback runs the actions right away (the transaction can only end rolled back): the undeclared
    // checked exception escaped from rollbackIfNotCommitted() of the nested scope, skipping the older actions.
    @Test
    void undeclaredCheckedRollbackActionFailureOfANestedRollbackIsLoggedAndTheOtherActionsRun() throws Exception {
        final List<String> ran = new ArrayList<>();
        final SqlTransaction outer = JdbcUtil.beginTransaction(dataSource, IsolationLevel.READ_COMMITTED);

        try {
            final SqlTransaction nested = JdbcUtil.beginTransaction(dataSource);
            nested.context().addRollbackAction(() -> ran.add("older"));
            nested.context().addRollbackAction(() -> sneakyThrow(new IOException("ID setter failed")));

            assertDoesNotThrow(nested::rollbackIfNotCommitted);

            assertEquals(List.of("older"), ran);
            assertEquals(SqlTransaction.Status.MARKED_ROLLBACK, outer.status());
        } finally {
            outer.rollbackIfNotCommitted();
        }

        assertEquals(SqlTransaction.Status.ROLLED_BACK, outer.status());
        verify(connection).close();
    }

    @Test
    void interruptedRollbackActionResetsTheInterruptFlag() throws Exception {
        final SqlTransaction transaction = JdbcUtil.beginTransaction(dataSource, IsolationLevel.READ_COMMITTED);
        transaction.context().addRollbackAction(() -> sneakyThrow(new InterruptedException("interrupted")));

        assertDoesNotThrow(transaction::rollbackIfNotCommitted);

        assertTrue(Thread.interrupted(), "the interrupt reported by the action must not be lost");
        verify(connection).close();
    }

    // ---- commit actions

    @Test
    void undeclaredCheckedCommitActionFailureIsLoggedAndTheLaterActionsAndTheCleanupStillRun() throws Exception {
        final List<String> ran = new ArrayList<>();
        final SqlTransaction transaction = JdbcUtil.beginTransaction(dataSource, IsolationLevel.READ_COMMITTED);
        transaction.context().addCommitAction(null, () -> sneakyThrow(new IOException("invalidation failed")));
        transaction.context().addCommitAction(null, () -> ran.add("later"));

        assertDoesNotThrow(transaction::commit);

        assertEquals(List.of("later"), ran);
        assertEquals(SqlTransaction.Status.COMMITTED, transaction.status());
        verify(connection).setAutoCommit(true);
        verify(connection).close();
        assertFalse(JdbcUtil.isInTransaction(dataSource));
    }

    // A commit action's InterruptedException was logged with the interrupt flag left cleared.
    @Test
    void interruptedCommitActionResetsTheInterruptFlag() throws Exception {
        final SqlTransaction transaction = JdbcUtil.beginTransaction(dataSource, IsolationLevel.READ_COMMITTED);
        transaction.context().addCommitAction(null, () -> sneakyThrow(new InterruptedException("interrupted")));

        assertDoesNotThrow(transaction::commit);

        assertTrue(Thread.interrupted(), "the interrupt reported by the action must not be lost");
        assertEquals(SqlTransaction.Status.COMMITTED, transaction.status());
        verify(connection).close();
    }

    // ---- beginning a transaction or a scope

    @Test
    void undeclaredCheckedAutoCommitFailureInBeginTransactionRestoresAndReleasesTheConnection() throws Exception {
        final IOException failure = new IOException("setAutoCommit failed");
        doAnswer(invocation -> {
            throw failure;
        }).when(connection).setAutoCommit(false);

        assertSame(failure, assertThrows(Exception.class, () -> JdbcUtil.beginTransaction(dataSource, IsolationLevel.SERIALIZABLE)));

        verify(connection).setAutoCommit(true); // attempted, so restored
        verify(connection, never()).setTransactionIsolation(Connection.TRANSACTION_SERIALIZABLE);
        verify(connection).close();
        assertFalse(JdbcUtil.isInTransaction(dataSource));
    }

    @Test
    void undeclaredCheckedIsolationFailureInBeginTransactionRestoresBothSettingsAndReleasesTheConnection() throws Exception {
        final IOException failure = new IOException("setTransactionIsolation failed");
        doAnswer(invocation -> {
            throw failure;
        }).when(connection).setTransactionIsolation(Connection.TRANSACTION_SERIALIZABLE);

        assertSame(failure, assertThrows(Exception.class, () -> JdbcUtil.beginTransaction(dataSource, IsolationLevel.SERIALIZABLE)));

        verify(connection).setAutoCommit(true);
        verify(connection).setTransactionIsolation(Connection.TRANSACTION_READ_COMMITTED);
        verify(connection).close();
        assertFalse(JdbcUtil.isInTransaction(dataSource));
    }

    @Test
    void undeclaredCheckedIsolationFailureEnteringANestedScopeRestoresTheLevelAndOpensNoScope() throws Exception {
        final IOException failure = new IOException("setTransactionIsolation failed");
        final SqlTransaction outer = JdbcUtil.beginTransaction(dataSource, IsolationLevel.READ_COMMITTED);

        try {
            doAnswer(invocation -> {
                throw failure;
            }).when(connection).setTransactionIsolation(Connection.TRANSACTION_SERIALIZABLE);

            assertSame(failure, assertThrows(Exception.class, () -> JdbcUtil.beginTransaction(dataSource, IsolationLevel.SERIALIZABLE)));

            verify(connection).setTransactionIsolation(Connection.TRANSACTION_READ_COMMITTED); // restored
            assertEquals(1, outer.context().depth());
            assertEquals(IsolationLevel.READ_COMMITTED, outer.context().isolationLevel());
            assertEquals(SqlTransaction.Status.ACTIVE, outer.status());

            outer.commit();
        } finally {
            outer.rollbackIfNotCommitted();
        }

        assertEquals(SqlTransaction.Status.COMMITTED, outer.status());
        verify(connection).commit();
        verify(connection).close();
    }

    // ---- completing a scope

    // A nested commit() whose isolation restore fails keeps the scope open for its cleanup call, and rethrows the failure as
    // it is: an undeclared checked exception was neither wrapped nor replaced.
    @Test
    void undeclaredCheckedIsolationRestoreFailureOfANestedCommitKeepsTheScopeOpen() throws Exception {
        final IOException restoreFailure = new IOException("setTransactionIsolation failed");
        final SqlTransaction outer = JdbcUtil.beginTransaction(dataSource, IsolationLevel.READ_COMMITTED);

        try {
            final SqlTransaction nested = JdbcUtil.beginTransaction(dataSource, IsolationLevel.SERIALIZABLE);
            doAnswer(invocation -> {
                throw restoreFailure;
            }).doNothing().when(connection).setTransactionIsolation(Connection.TRANSACTION_READ_COMMITTED);

            assertSame(restoreFailure, assertThrows(Exception.class, nested::commit));

            assertEquals(2, outer.context().depth());
            assertEquals(SqlTransaction.Status.ACTIVE, outer.status());
            assertFalse(nested.isCompleted());

            nested.rollbackIfNotCommitted(); // completes it: the restore succeeds now

            assertTrue(nested.isCompleted());
            assertEquals(1, outer.context().depth());
            assertEquals(SqlTransaction.Status.MARKED_ROLLBACK, outer.status());
            assertThrows(RollbackOnlyException.class, outer::commit);
        } finally {
            outer.rollbackIfNotCommitted();
        }

        verify(connection, never()).commit();
        verify(connection).rollback();
        verify(connection).close();
        assertFalse(JdbcUtil.isInTransaction(dataSource));
    }

    // An undeclared checked exception of the database COMMIT: the transaction is rolled back, the commit actions run (the
    // database may have committed), the connection is released, and the original failure is thrown as it is.
    @Test
    void undeclaredCheckedCommitFailureRollsBackReleasesAndIsThrownAsIs() throws Exception {
        final IOException commitFailure = new IOException("commit failed");
        final List<String> ran = new ArrayList<>();
        final SqlTransaction transaction = JdbcUtil.beginTransaction(dataSource, IsolationLevel.READ_COMMITTED);
        transaction.context().addCommitAction(null, () -> ran.add("commit action"));
        doAnswer(invocation -> {
            throw commitFailure;
        }).when(connection).commit();

        assertSame(commitFailure, assertThrows(Exception.class, transaction::commit));

        assertEquals(0, commitFailure.getSuppressed().length);
        assertEquals(List.of("commit action"), ran);
        assertEquals(SqlTransaction.Status.ROLLED_BACK, transaction.status());
        verify(connection).rollback();
        verify(connection).setAutoCommit(true);
        verify(connection).close();
        assertFalse(JdbcUtil.isInTransaction(dataSource));
    }

    // An undeclared checked exception of the database ROLLBACK: FAILED_ROLLBACK, the connection aborted and released without
    // restoring its settings (which would commit the pending work on a pool that restores auto-commit).
    @Test
    void undeclaredCheckedRollbackFailureAbortsAndReleasesTheConnection() throws Exception {
        final IOException rollbackFailure = new IOException("rollback failed");
        final SqlTransaction transaction = JdbcUtil.beginTransaction(dataSource, IsolationLevel.READ_COMMITTED);
        doAnswer(invocation -> {
            throw rollbackFailure;
        }).when(connection).rollback();

        assertSame(rollbackFailure, assertThrows(Exception.class, transaction::rollbackIfNotCommitted));

        assertEquals(SqlTransaction.Status.FAILED_ROLLBACK, transaction.status());
        verify(connection).abort(any(Executor.class));
        verify(connection, never()).setAutoCommit(true);
        verify(connection).close();
        assertFalse(JdbcUtil.isInTransaction(dataSource));
    }

    @Test
    void undeclaredCheckedAbortFailureIsLoggedAndTheConnectionIsStillReleased() throws Exception {
        final SQLException rollbackFailure = new SQLException("rollback failed");
        final SqlTransaction transaction = JdbcUtil.beginTransaction(dataSource, IsolationLevel.READ_COMMITTED);
        doThrow(rollbackFailure).when(connection).rollback();
        doAnswer(invocation -> {
            throw new IOException("abort failed");
        }).when(connection).abort(any(Executor.class));

        final UncheckedSQLException thrown = assertThrows(UncheckedSQLException.class, transaction::rollbackIfNotCommitted);

        assertSame(rollbackFailure, thrown.getCause());
        assertEquals(0, thrown.getSuppressed().length);
        assertEquals(SqlTransaction.Status.FAILED_ROLLBACK, transaction.status());
        verify(connection).close();
    }

    // The abort's InterruptedException was logged with the interrupt flag left cleared.
    @Test
    void interruptedAbortResetsTheInterruptFlag() throws Exception {
        final SqlTransaction transaction = JdbcUtil.beginTransaction(dataSource, IsolationLevel.READ_COMMITTED);
        doThrow(new SQLException("rollback failed")).when(connection).rollback();
        doAnswer(invocation -> {
            throw new InterruptedException("interrupted");
        }).when(connection).abort(any(Executor.class));

        assertThrows(UncheckedSQLException.class, transaction::rollbackIfNotCommitted);

        assertTrue(Thread.interrupted(), "the interrupt reported by the abort must not be lost");
        verify(connection).close();
    }

    // The cleanup after a failed rollback was a try/finally chain: an Error of the abort was replaced by a failure of the
    // release, which also skipped taking the aborted connection from Spring. Every step is attempted now, the first failure
    // propagating with the later ones attached, as in the cleanup after a successful completion.
    @Test
    void failedRollbackCleanupKeepsTheAbortErrorPrimaryOverAReleaseFailure() throws Exception {
        final AssertionError abortError = new AssertionError("abort failed");
        final AssertionError releaseError = new AssertionError("release failed");
        final SQLException rollbackFailure = new SQLException("rollback failed");
        final SqlTransaction transaction = JdbcUtil.beginTransaction(dataSource, IsolationLevel.READ_COMMITTED);
        assertTrue(JdbcUtil.isSpringTransactionalUsed(dataSource), "the connection is acquired through Spring here");
        doThrow(rollbackFailure).when(connection).rollback();
        doThrow(abortError).when(connection).abort(any(Executor.class));

        try (MockedStatic<JdbcUtil> jdbcUtil = Mockito.mockStatic(JdbcUtil.class, Mockito.CALLS_REAL_METHODS)) {
            jdbcUtil.when(() -> JdbcUtil.releaseSpringManagedConnection(any(Connection.class), any(DataSource.class))).thenThrow(releaseError);

            final UncheckedSQLException thrown = assertThrows(UncheckedSQLException.class, transaction::rollbackIfNotCommitted);

            assertSame(rollbackFailure, thrown.getCause());
            assertArrayEquals(new Throwable[] { abortError }, thrown.getSuppressed());
            assertArrayEquals(new Throwable[] { releaseError }, abortError.getSuppressed());
            jdbcUtil.verify(() -> JdbcUtil.detachAbortedSpringConnection(connection, dataSource));
        }

        assertEquals(SqlTransaction.Status.FAILED_ROLLBACK, transaction.status());
        assertFalse(JdbcUtil.isInTransaction(dataSource));
    }

    @Test
    void failedRollbackCleanupStillDetachesFromSpringWhenTheReleaseFails() throws Exception {
        final AssertionError releaseError = new AssertionError("release failed");
        final SqlTransaction transaction = JdbcUtil.beginTransaction(dataSource, IsolationLevel.READ_COMMITTED);
        doThrow(new SQLException("rollback failed")).when(connection).rollback();

        try (MockedStatic<JdbcUtil> jdbcUtil = Mockito.mockStatic(JdbcUtil.class, Mockito.CALLS_REAL_METHODS)) {
            jdbcUtil.when(() -> JdbcUtil.releaseSpringManagedConnection(any(Connection.class), any(DataSource.class))).thenThrow(releaseError);

            final UncheckedSQLException thrown = assertThrows(UncheckedSQLException.class, transaction::rollbackIfNotCommitted);

            assertArrayEquals(new Throwable[] { releaseError }, thrown.getSuppressed());
            jdbcUtil.verify(() -> JdbcUtil.detachAbortedSpringConnection(connection, dataSource), times(1));
        }

        verify(connection).abort(any(Executor.class));
    }

    // ---- scopes left open inside the completing one (no commit() or cleanup call)

    // Exiting a leaked scope restores the isolation level; its undeclared checked failure is thrown after the outermost scope
    // has rolled back and released the connection (by commit(), with the reason the commit failed attached).
    @ParameterizedTest(name = "commit={0}")
    @ValueSource(booleans = { true, false })
    void undeclaredCheckedFailureExitingALeakedScopeIsThrownAfterTheRollback(final boolean commit) throws Exception {
        final IOException restoreFailure = new IOException("setTransactionIsolation failed");
        final SqlTransaction outer = JdbcUtil.beginTransaction(dataSource, IsolationLevel.READ_COMMITTED);
        final SqlTransaction leaked = JdbcUtil.beginTransaction(dataSource, IsolationLevel.SERIALIZABLE);
        doAnswer(invocation -> {
            throw restoreFailure;
        }).doNothing().when(connection).setTransactionIsolation(Connection.TRANSACTION_READ_COMMITTED);

        assertSame(restoreFailure, assertThrows(Exception.class, commit ? outer::commit : outer::rollbackIfNotCommitted));

        if (commit) {
            assertEquals(1, restoreFailure.getSuppressed().length);
            assertInstanceOf(RollbackOnlyException.class, restoreFailure.getSuppressed()[0]);
        } else {
            assertEquals(0, restoreFailure.getSuppressed().length);
        }

        assertEquals(SqlTransaction.Status.ROLLED_BACK, outer.status());
        assertTrue(leaked.isCompleted());
        assertDoesNotThrow(outer::rollbackIfNotCommitted); // completed: a no-op
        verify(connection, never()).commit();
        verify(connection).rollback();
        verify(connection).setAutoCommit(true);
        verify(connection).close();
        assertFalse(JdbcUtil.isInTransaction(dataSource));
    }

    // The connection cleanup caught SQLException and RuntimeException | Error only: an undeclared checked exception from
    // restoring auto-commit escaped at once, skipping the isolation reset and the release, which leaked the connection.
    @ParameterizedTest(name = "commit={0}")
    @ValueSource(booleans = { true, false })
    void undeclaredCheckedAutoCommitResetFailureStillResetsIsolationAndReleasesConnection(final boolean commit) throws Exception {
        final IOException resetFailure = new IOException("setAutoCommit failed");
        final SqlTransaction transaction = JdbcUtil.beginTransaction(dataSource, IsolationLevel.READ_COMMITTED);
        doAnswer(invocation -> {
            throw resetFailure;
        }).when(connection).setAutoCommit(true);

        final Exception thrown = assertThrows(Exception.class, commit ? transaction::commit : transaction::rollbackIfNotCommitted);

        assertSame(resetFailure, thrown);
        assertEquals(commit ? SqlTransaction.Status.COMMITTED : SqlTransaction.Status.ROLLED_BACK, transaction.status());
        verify(connection).setTransactionIsolation(Connection.TRANSACTION_READ_COMMITTED);
        verify(connection).close();
        assertFalse(JdbcUtil.isInTransaction(dataSource));
    }

    @Test
    void laterCleanupFailuresAreAttachedToTheFirstUndeclaredCheckedOne() throws Exception {
        final IOException resetFailure = new IOException("setAutoCommit failed");
        final IllegalStateException isolationFailure = new IllegalStateException("setTransactionIsolation failed");
        final SqlTransaction transaction = JdbcUtil.beginTransaction(dataSource, IsolationLevel.READ_COMMITTED);
        doAnswer(invocation -> {
            throw resetFailure;
        }).when(connection).setAutoCommit(true);
        doAnswer(invocation -> {
            throw isolationFailure;
        }).when(connection).setTransactionIsolation(Connection.TRANSACTION_READ_COMMITTED);

        assertSame(resetFailure, assertThrows(Exception.class, transaction::commit));
        assertArrayEquals(new Throwable[] { isolationFailure }, resetFailure.getSuppressed());
        verify(connection).close();
    }

    // Exiting a nested scope that changed the isolation level restores the enclosing one. A forced exit (rollbackIfNotCommitted)
    // caught SQLException | RuntimeException | Error only: an undeclared checked failure left the scope uncompleted and the
    // transaction ACTIVE, so its outermost commit committed the work of the rolled-back scope.
    @Test
    void undeclaredCheckedIsolationRestoreFailureStillExitsNestedScopeAndMarksRollbackOnly() throws Exception {
        final IOException restoreFailure = new IOException("setTransactionIsolation failed");
        final SqlTransaction outer = JdbcUtil.beginTransaction(dataSource, IsolationLevel.READ_COMMITTED);

        try {
            final SqlTransaction nested = JdbcUtil.beginTransaction(dataSource, IsolationLevel.SERIALIZABLE);
            doAnswer(invocation -> {
                throw restoreFailure;
            }).doNothing().when(connection).setTransactionIsolation(Connection.TRANSACTION_READ_COMMITTED);

            assertSame(restoreFailure, assertThrows(Exception.class, nested::rollbackIfNotCommitted));

            assertDoesNotThrow(nested::rollbackIfNotCommitted); // completed: a no-op
            assertEquals(SqlTransaction.Status.MARKED_ROLLBACK, outer.status());
            assertThrows(RollbackOnlyException.class, outer::commit);
            verify(connection, org.mockito.Mockito.never()).commit();
        } finally {
            outer.rollbackIfNotCommitted();
        }

        verify(connection).close();
        assertFalse(JdbcUtil.isInTransaction(dataSource));
    }
}
