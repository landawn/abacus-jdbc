package com.landawn.abacus.jdbc;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.lang.reflect.Field;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.concurrent.atomic.AtomicInteger;

import javax.sql.DataSource;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.MockitoAnnotations;

import com.landawn.abacus.TestBase;
import com.landawn.abacus.exception.UncheckedSQLException;
import com.landawn.abacus.util.Throwables;

public class SqlTransactionTest extends TestBase {

    @Mock
    private DataSource dataSource;

    @Mock
    private Connection connection;

    @BeforeEach
    public void setUp() throws SQLException {
        MockitoAnnotations.openMocks(this);
        when(dataSource.getConnection()).thenReturn(connection);
        when(connection.getAutoCommit()).thenReturn(true);
        when(connection.getTransactionIsolation()).thenReturn(Connection.TRANSACTION_READ_COMMITTED);
    }

    @Test
    public void testId() throws SQLException {
        final SqlTransaction transaction = JdbcUtil.beginTransaction(dataSource, IsolationLevel.READ_COMMITTED);

        assertNotNull(transaction.id());
        assertTrue(transaction.id().contains(String.valueOf(System.identityHashCode(dataSource))));
    }

    @Test
    public void testConnection() throws SQLException {
        final SqlTransaction transaction = JdbcUtil.beginTransaction(dataSource, IsolationLevel.READ_COMMITTED);

        assertSame(connection, transaction.connection());
    }

    @Test
    public void testIsolationLevel() throws SQLException {
        final SqlTransaction transaction = JdbcUtil.beginTransaction(dataSource, IsolationLevel.READ_COMMITTED);

        assertEquals(IsolationLevel.READ_COMMITTED, transaction.isolationLevel());
    }

    @Test
    public void testStatus() throws SQLException {
        final SqlTransaction transaction = JdbcUtil.beginTransaction(dataSource, IsolationLevel.READ_COMMITTED);

        assertEquals(Transaction.Status.ACTIVE, transaction.status());
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

        assertEquals(Transaction.Status.COMMITTED, transaction.status());
        assertFalse(transaction.isActive());
        verify(connection).commit();
        verify(connection).setAutoCommit(true);
    }

    @Test
    public void testCommitWhenOriginalAutoCommitFalse() throws SQLException {
        when(connection.getAutoCommit()).thenReturn(false);
        final SqlTransaction transaction = JdbcUtil.beginTransaction(dataSource, IsolationLevel.READ_COMMITTED);

        transaction.commit();

        assertEquals(Transaction.Status.COMMITTED, transaction.status());
        verify(connection).commit();
        verify(connection, times(2)).setAutoCommit(false);
    }

    @Test
    public void testCommitFailure() throws SQLException {
        final SqlTransaction transaction = JdbcUtil.beginTransaction(dataSource, IsolationLevel.READ_COMMITTED);
        doThrow(new SQLException("Commit failed")).when(connection).commit();

        assertThrows(UncheckedSQLException.class, transaction::commit);
        assertEquals(Transaction.Status.ROLLED_BACK, transaction.status());
        verify(connection).rollback();
    }

    @Test
    public void testRollback() throws SQLException {
        final SqlTransaction transaction = JdbcUtil.beginTransaction(dataSource, IsolationLevel.READ_COMMITTED);

        transaction.rollback();

        assertEquals(Transaction.Status.ROLLED_BACK, transaction.status());
        assertFalse(transaction.isActive());
        verify(connection).rollback();
    }

    @Test
    public void testRollbackWhenOriginalAutoCommitFalse() throws SQLException {
        when(connection.getAutoCommit()).thenReturn(false);
        final SqlTransaction transaction = JdbcUtil.beginTransaction(dataSource, IsolationLevel.READ_COMMITTED);

        transaction.rollback();

        assertEquals(Transaction.Status.ROLLED_BACK, transaction.status());
        verify(connection).rollback();
        verify(connection, times(2)).setAutoCommit(false);
    }

    @Test
    public void testRollbackIfNotCommitted() throws SQLException {
        final SqlTransaction transaction = JdbcUtil.beginTransaction(dataSource, IsolationLevel.READ_COMMITTED);

        transaction.rollbackIfNotCommitted();

        assertEquals(Transaction.Status.ROLLED_BACK, transaction.status());
        verify(connection).rollback();
    }

    @Test
    public void testRollbackIfNotCommitted_AfterCommit() throws SQLException {
        final SqlTransaction transaction = JdbcUtil.beginTransaction(dataSource, IsolationLevel.READ_COMMITTED);

        transaction.commit();
        transaction.rollbackIfNotCommitted();

        assertEquals(Transaction.Status.COMMITTED, transaction.status());
        verify(connection, times(1)).commit();
        verify(connection, never()).rollback();
    }

    @Test
    public void testRollbackIfNotCommitted_NestedMarksRollback() throws SQLException {
        final SqlTransaction transaction = JdbcUtil.beginTransaction(dataSource, IsolationLevel.DEFAULT);
        transaction.incrementAndGetRef(IsolationLevel.SERIALIZABLE, true);

        transaction.rollbackIfNotCommitted();

        assertEquals(Transaction.Status.MARKED_ROLLBACK, transaction.status());
        verify(connection, never()).rollback();
    }

    @Test
    public void testCommit_MarkedRollback_RollsBackInstead() throws SQLException {
        final SqlTransaction transaction = JdbcUtil.beginTransaction(dataSource, IsolationLevel.DEFAULT);
        transaction.incrementAndGetRef(IsolationLevel.SERIALIZABLE, false);
        transaction.rollbackIfNotCommitted();

        transaction.commit();

        assertEquals(Transaction.Status.ROLLED_BACK, transaction.status());
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
    public void testCallOutsideTransaction() throws Exception {
        final SqlTransaction transaction = JdbcUtil.beginTransaction(dataSource, IsolationLevel.READ_COMMITTED);

        final String result = transaction.callOutsideTransaction(() -> "test result");

        assertEquals("test result", result);
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
    public void testRunNotInMe() throws Exception {
        final SqlTransaction transaction = JdbcUtil.beginTransaction(dataSource, IsolationLevel.READ_COMMITTED);
        final boolean[] executed = { false };
        final Throwables.Runnable<Exception> runnable = () -> executed[0] = true;

        transaction.runNotInMe(runnable);

        assertTrue(executed[0]);
    }

    @Test
    public void testCallNotInMe() throws Exception {
        final SqlTransaction transaction = JdbcUtil.beginTransaction(dataSource, IsolationLevel.READ_COMMITTED);
        final Throwables.Callable<String, Exception> callable = () -> "test result";

        final String result = transaction.callNotInMe(callable);

        assertEquals("test result", result);
    }

    @Test
    public void testClose() throws SQLException {
        final SqlTransaction transaction = JdbcUtil.beginTransaction(dataSource, IsolationLevel.READ_COMMITTED);

        transaction.close();

        assertEquals(Transaction.Status.ROLLED_BACK, transaction.status());
        verify(connection).rollback();
    }

    @Test
    public void testHashCode() throws SQLException {
        final SqlTransaction transaction = JdbcUtil.beginTransaction(dataSource, IsolationLevel.READ_COMMITTED);

        assertEquals(transaction.id().hashCode(), transaction.hashCode());
    }

    @Test
    public void testEquals() throws SQLException {
        final SqlTransaction transaction1 = JdbcUtil.beginTransaction(dataSource, IsolationLevel.READ_COMMITTED);
        final SqlTransaction transaction2 = JdbcUtil.beginTransaction(dataSource, IsolationLevel.READ_COMMITTED);

        assertEquals(transaction1, transaction2);
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
    public void testIncrementAndGetRef() throws SQLException {
        final SqlTransaction transaction = JdbcUtil.beginTransaction(dataSource, IsolationLevel.READ_COMMITTED);

        final int refCount = transaction.incrementAndGetRef(IsolationLevel.SERIALIZABLE, false);

        assertEquals(2, refCount);
        assertEquals(IsolationLevel.SERIALIZABLE, transaction.isolationLevel());
        verify(connection).setTransactionIsolation(Connection.TRANSACTION_SERIALIZABLE);
    }

    @Test
    public void testIncrementAndGetRef_AfterCommit() throws SQLException {
        final SqlTransaction transaction = JdbcUtil.beginTransaction(dataSource, IsolationLevel.READ_COMMITTED);
        transaction.commit();

        assertThrows(IllegalStateException.class, () -> transaction.incrementAndGetRef(IsolationLevel.READ_COMMITTED, false));
    }

    @Test
    public void testDecrementAndGetRef_RestoresOriginalIsolationForDefault() throws SQLException {
        final SqlTransaction transaction = JdbcUtil.beginTransaction(dataSource, IsolationLevel.DEFAULT);
        transaction.incrementAndGetRef(IsolationLevel.SERIALIZABLE, false);

        final int refCount = transaction.decrementAndGetRef();

        assertEquals(1, refCount);
        assertEquals(IsolationLevel.DEFAULT, transaction.isolationLevel());
        verify(connection).setTransactionIsolation(Connection.TRANSACTION_SERIALIZABLE);
        verify(connection).setTransactionIsolation(Connection.TRANSACTION_READ_COMMITTED);
    }

    @Test
    public void testIsForUpdateOnly() throws SQLException {
        final SqlTransaction transaction = JdbcUtil.beginTransaction(dataSource, IsolationLevel.READ_COMMITTED);

        assertFalse(transaction.isForUpdateOnly());

        transaction.incrementAndGetRef(IsolationLevel.READ_COMMITTED, true);
        assertTrue(transaction.isForUpdateOnly());
    }

    @Test
    public void testGetTransactionId() {
        final String id = SqlTransaction.getTransactionId(dataSource, SqlTransaction.CreatedBy.JDBC_UTIL);
        assertNotNull(id);
        assertTrue(id.contains(String.valueOf(System.identityHashCode(dataSource))));
        assertTrue(id.contains(String.valueOf(Thread.currentThread().getId())));
    }

    // commit() when refCount < 0 (already committed): should log warn and return (lines 299-300)
    @Test
    public void testCommit_WhenAlreadyCommitted_WarnsAndIgnores() throws SQLException {
        final SqlTransaction transaction = JdbcUtil.beginTransaction(dataSource, IsolationLevel.READ_COMMITTED);
        transaction.commit();
        // second commit: refCount < 0, should silently ignore
        transaction.commit();
        // connection.commit() should only have been called once
        verify(connection, times(1)).commit();
    }

    // rollback() when refCount < 0 (already rolled back): should log warn and return (lines 391-393)
    @Test
    public void testRollback_WhenAlreadyRolledBack_WarnsAndIgnores() throws SQLException {
        final SqlTransaction transaction = JdbcUtil.beginTransaction(dataSource, IsolationLevel.READ_COMMITTED);
        transaction.rollback();
        // second rollback: refCount < 0, should silently ignore
        transaction.rollback();
        verify(connection, times(1)).rollback();
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

            assertSame(transaction1, transaction2);

            transaction1.commit();
            transaction2.commit();

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

    // rollbackIfNotCommitted() when status is FAILED_COMMIT should proceed with rollback (not early-return).
    // Before the fix, FAILED_COMMIT was included in the early-return guard, silently skipping the rollback.
    @Test
    public void testRollbackIfNotCommitted_WhenStatusIsFailedCommit_ProceedsWithRollback() throws Exception {
        final SqlTransaction transaction = JdbcUtil.beginTransaction(dataSource, IsolationLevel.READ_COMMITTED);
        // Force status to FAILED_COMMIT via reflection to simulate the transient state
        final Field statusField = SqlTransaction.class.getDeclaredField("_status");
        statusField.setAccessible(true);
        statusField.set(transaction, Transaction.Status.FAILED_COMMIT);

        transaction.rollbackIfNotCommitted();

        assertEquals(Transaction.Status.ROLLED_BACK, transaction.status());
        verify(connection).rollback();
    }

    // rollback() on a nested transaction (refCount > 0) marks status as MARKED_ROLLBACK without executing actual rollback
    @Test
    public void testRollback_WhenNested_MarksForRollback() throws SQLException {
        final SqlTransaction transaction = JdbcUtil.beginTransaction(dataSource, IsolationLevel.READ_COMMITTED);
        transaction.incrementAndGetRef(IsolationLevel.READ_COMMITTED, false);

        // first rollback: nested (refCount > 0) -> marks MARKED_ROLLBACK, does NOT actually rollback
        transaction.rollback();

        assertEquals(Transaction.Status.MARKED_ROLLBACK, transaction.status());
        verify(connection, never()).rollback();

        // cleanup: final rollback (refCount reaches 0) -> actually rolls back
        transaction.rollback();
        assertEquals(Transaction.Status.ROLLED_BACK, transaction.status());
        verify(connection, times(1)).rollback();
    }

    // rollbackIfNotCommitted() after a direct rollbackIfNotCommitted() returns early on second call (L436)
    @Test
    public void testRollbackIfNotCommitted_WhenAlreadyRolledBackDirectly_DoesNothing() throws SQLException {
        final SqlTransaction transaction = JdbcUtil.beginTransaction(dataSource, IsolationLevel.READ_COMMITTED);

        transaction.rollbackIfNotCommitted(); // actual rollback
        transaction.rollbackIfNotCommitted(); // early return via L436 (status already ROLLED_BACK)

        assertEquals(Transaction.Status.ROLLED_BACK, transaction.status());
        verify(connection, times(1)).rollback();
    }

    // rollback() when connection.rollback() throws SQLException -> UncheckedSQLException, status=FAILED_ROLLBACK
    @Test
    public void testRollback_WhenRollbackFails_ThrowsUncheckedSQLException() throws SQLException {
        final SqlTransaction transaction = JdbcUtil.beginTransaction(dataSource, IsolationLevel.READ_COMMITTED);
        doThrow(new SQLException("Rollback failed")).when(connection).rollback();

        assertThrows(UncheckedSQLException.class, transaction::rollback);
        assertEquals(Transaction.Status.FAILED_ROLLBACK, transaction.status());
    }

    // resetAndCloseConnection() swallows SQLException from setAutoCommit (L507-508)
    @Test
    public void testRollback_WhenResetConnectionFails_SwallowsException() throws SQLException {
        final SqlTransaction transaction = JdbcUtil.beginTransaction(dataSource, IsolationLevel.READ_COMMITTED);
        doThrow(new SQLException("setAutoCommit failed")).when(connection).setAutoCommit(true);

        // rollback should succeed even if resetting connection fails
        assertDoesNotThrow((org.junit.jupiter.api.function.Executable) transaction::rollback);
        assertEquals(Transaction.Status.ROLLED_BACK, transaction.status());
        verify(connection).rollback();
    }

    // commit() when transaction already committed logs warning and returns (L298-300)
    @Test
    public void testCommit_WhenAlreadyCommitted_LogsWarningAndReturns() throws SQLException {
        final SqlTransaction transaction = JdbcUtil.beginTransaction(dataSource, IsolationLevel.READ_COMMITTED);
        transaction.commit(); // first commit succeeds, refCount → 0
        // second commit: refCount goes to -1 → L298-300 logs warning and returns silently
        assertDoesNotThrow((org.junit.jupiter.api.function.Executable) transaction::commit);
        verify(connection, times(1)).commit(); // only one actual DB commit
    }

    // commit() fails, then rollback also fails → rollback exception swallowed, original rethrown (L335-337)
    @Test
    public void testCommit_WhenRollbackAlsoFails_RollbackExceptionSwallowed() throws SQLException {
        final SqlTransaction transaction = JdbcUtil.beginTransaction(dataSource, IsolationLevel.READ_COMMITTED);
        doThrow(new SQLException("Commit failed")).when(connection).commit();
        doThrow(new SQLException("Rollback also failed")).when(connection).rollback();

        assertThrows(UncheckedSQLException.class, transaction::commit);
        assertEquals(Transaction.Status.FAILED_ROLLBACK, transaction.status());
        verify(connection).rollback();
    }

    // rollback() after commit logs warning and returns (L391-393)
    @Test
    public void testRollback_WhenAlreadyCommitted_LogsWarningAndReturns() throws SQLException {
        final SqlTransaction transaction = JdbcUtil.beginTransaction(dataSource, IsolationLevel.READ_COMMITTED);
        transaction.commit();
        // second rollback: refCount goes to -1 → L391-393 logs warning and returns silently
        assertDoesNotThrow((org.junit.jupiter.api.function.Executable) transaction::rollback);
        verify(connection, never()).rollback();
    }

    // incrementAndGetRef() throws UncheckedSQLException when setTransactionIsolation fails (L544-545)
    @Test
    public void testIncrementAndGetRef_WhenSetIsolationFails_ThrowsUncheckedSQLException() throws SQLException {
        final SqlTransaction transaction = JdbcUtil.beginTransaction(dataSource, IsolationLevel.READ_COMMITTED);
        doThrow(new SQLException("setIsolation failed")).when(connection).setTransactionIsolation(org.mockito.ArgumentMatchers.anyInt());
        assertThrows(UncheckedSQLException.class, () -> transaction.incrementAndGetRef(IsolationLevel.SERIALIZABLE, false));
        transaction.rollbackIfNotCommitted();
    }

    // decrementAndGetRef() throws UncheckedSQLException when restoring isolation fails (L596-597)
    @Test
    public void testDecrementAndGetRef_WhenRestoreIsolationFails_ThrowsUncheckedSQLException() throws SQLException {
        final SqlTransaction transaction = JdbcUtil.beginTransaction(dataSource, IsolationLevel.READ_COMMITTED);
        // increment to get refCount=2; this works since setTransactionIsolation not yet throwing
        transaction.incrementAndGetRef(IsolationLevel.SERIALIZABLE, false);
        // now make setTransactionIsolation throw on restore
        doThrow(new SQLException("restore failed")).when(connection).setTransactionIsolation(org.mockito.ArgumentMatchers.anyInt());
        // rollback() → decrementAndGetRef() → res=1 → tries to restore isolation → throws
        assertThrows(UncheckedSQLException.class, transaction::rollback);
    }

    // runOutsideTransaction(): another transaction opened during cmd causes IllegalStateException (L699-705)
    @Test
    public void testRunOutsideTransaction_WhenAnotherTransactionOpenedInsideCmd_ThrowsISE() throws SQLException {
        final SqlTransaction outerTx = JdbcUtil.beginTransaction(dataSource, IsolationLevel.READ_COMMITTED);

        try {
            // cmd starts another transaction with same datasource → same _id → conflict in finally
            assertThrows(IllegalStateException.class, () -> outerTx.runOutsideTransaction(() -> {
                JdbcUtil.beginTransaction(dataSource, IsolationLevel.READ_COMMITTED);
            }));
        } finally {
            // clean up inner transaction that remains in the map
            final SqlTransaction remaining = SqlTransaction.getTransaction(dataSource, SqlTransaction.CreatedBy.JDBC_UTIL);
            if (remaining != null) {
                remaining.rollbackIfNotCommitted();
            }
        }
    }

    // callOutsideTransaction(): another transaction opened during cmd causes IllegalStateException (L762-768)
    @Test
    public void testCallOutsideTransaction_WhenAnotherTransactionOpenedInsideCmd_ThrowsISE() throws SQLException {
        final SqlTransaction outerTx = JdbcUtil.beginTransaction(dataSource, IsolationLevel.READ_COMMITTED);

        try {
            assertThrows(IllegalStateException.class, () -> outerTx.callOutsideTransaction(() -> {
                JdbcUtil.beginTransaction(dataSource, IsolationLevel.READ_COMMITTED);
                return null;
            }));
        } finally {
            final SqlTransaction remaining = SqlTransaction.getTransaction(dataSource, SqlTransaction.CreatedBy.JDBC_UTIL);
            if (remaining != null) {
                remaining.rollbackIfNotCommitted();
            }
        }
    }

    // After runOutsideTransaction throws ISE because a different transaction was opened in cmd,
    // cleaning up the original transaction (rollbackIfNotCommitted) must not remove the OTHER
    // transaction from the registry. Before the fix, decrementAndGetRef called
    // threadTransactionMap.remove(_id) unconditionally, evicting the unrelated transaction.
    @Test
    public void testCleanupAfterRunOutsideTransactionConflictDoesNotEvictOtherTransaction() throws Exception {
        clearThreadTransactionMap();

        final Connection connection2 = Mockito.mock(Connection.class);
        when(connection2.getAutoCommit()).thenReturn(true);
        when(connection2.getTransactionIsolation()).thenReturn(Connection.TRANSACTION_READ_COMMITTED);
        when(dataSource.getConnection()).thenReturn(connection, connection2);

        final SqlTransaction outerTx = JdbcUtil.beginTransaction(dataSource, IsolationLevel.READ_COMMITTED);
        SqlTransaction innerTx = null;

        try {
            assertThrows(IllegalStateException.class, () -> outerTx.runOutsideTransaction(() -> {
                JdbcUtil.beginTransaction(dataSource, IsolationLevel.READ_COMMITTED);
            }));

            // The inner transaction must be the one currently registered for this thread/ds.
            innerTx = SqlTransaction.getTransaction(dataSource, SqlTransaction.CreatedBy.JDBC_UTIL);
            assertNotNull(innerTx);
            assertNotSame(outerTx, innerTx);

            final SqlTransaction expectedInner = innerTx;

            // Cleaning up outerTx must not evict innerTx from the registry.
            outerTx.rollbackIfNotCommitted();

            assertSame(expectedInner, SqlTransaction.getTransaction(dataSource, SqlTransaction.CreatedBy.JDBC_UTIL));
        } finally {
            if (innerTx != null) {
                innerTx.rollbackIfNotCommitted();
            }
        }
    }

    // commit() when status is not ACTIVE: covers line 335 (IllegalStateException)
    @Test
    public void testCommit_WhenStatusNotActive_ThrowsISE() throws Exception {
        final SqlTransaction transaction = JdbcUtil.beginTransaction(dataSource, IsolationLevel.READ_COMMITTED);

        final Field refCountField = SqlTransaction.class.getDeclaredField("_refCount");
        refCountField.setAccessible(true);
        ((AtomicInteger) refCountField.get(transaction)).set(1);

        final Field statusField = SqlTransaction.class.getDeclaredField("_status");
        statusField.setAccessible(true);
        statusField.set(transaction, Transaction.Status.COMMITTED);

        assertThrows(IllegalStateException.class, transaction::commit);
    }

    // rollback() when status is not ACTIVE/MARKED_ROLLBACK/FAILED_COMMIT: covers line 430 (IllegalStateException)
    @Test
    public void testRollback_WhenStatusInvalid_ThrowsISE() throws Exception {
        final SqlTransaction transaction = JdbcUtil.beginTransaction(dataSource, IsolationLevel.READ_COMMITTED);

        final Field refCountField = SqlTransaction.class.getDeclaredField("_refCount");
        refCountField.setAccessible(true);
        ((AtomicInteger) refCountField.get(transaction)).set(1);

        final Field statusField = SqlTransaction.class.getDeclaredField("_status");
        statusField.setAccessible(true);
        statusField.set(transaction, Transaction.Status.COMMITTED);

        assertThrows(IllegalStateException.class, transaction::rollback);
    }

    // rollbackIfNotCommitted() when status is not valid: covers line 483 (IllegalStateException)
    @Test
    public void testRollbackIfNotCommitted_WhenStatusInvalid_ThrowsISE() throws Exception {
        final SqlTransaction transaction = JdbcUtil.beginTransaction(dataSource, IsolationLevel.READ_COMMITTED);

        final Field statusField = SqlTransaction.class.getDeclaredField("_status");
        statusField.setAccessible(true);
        statusField.set(transaction, null);

        assertThrows(IllegalStateException.class, transaction::rollbackIfNotCommitted);
    }

    // runOutsideTransaction(): cmd throws AND another transaction is opened inside → addSuppressed (line 763)
    @Test
    public void testRunOutsideTransaction_WhenCmdThrowsAndAnotherTransactionOpened_AddSuppressesISE() throws Exception {
        clearThreadTransactionMap();
        final SqlTransaction outerTx = JdbcUtil.beginTransaction(dataSource, IsolationLevel.READ_COMMITTED);
        try {
            final RuntimeException ex = assertThrows(RuntimeException.class, () -> outerTx.runOutsideTransaction(() -> {
                JdbcUtil.beginTransaction(dataSource, IsolationLevel.READ_COMMITTED);
                throw new RuntimeException("cmd error");
            }));
            assertTrue(ex.getSuppressed().length > 0);
            assertTrue(ex.getSuppressed()[0] instanceof IllegalStateException);
        } finally {
            final SqlTransaction inner = SqlTransaction.getTransaction(dataSource, SqlTransaction.CreatedBy.JDBC_UTIL);
            if (inner != null) {
                inner.rollbackIfNotCommitted();
            }
        }
    }

    // callOutsideTransaction(): cmd throws AND another transaction is opened inside → addSuppressed (line 826)
    @Test
    public void testCallOutsideTransaction_WhenCmdThrowsAndAnotherTransactionOpened_AddSuppressesISE() throws Exception {
        clearThreadTransactionMap();
        final SqlTransaction outerTx = JdbcUtil.beginTransaction(dataSource, IsolationLevel.READ_COMMITTED);
        try {
            final RuntimeException ex = assertThrows(RuntimeException.class, () -> outerTx.callOutsideTransaction(() -> {
                JdbcUtil.beginTransaction(dataSource, IsolationLevel.READ_COMMITTED);
                throw new RuntimeException("cmd error");
            }));
            assertTrue(ex.getSuppressed().length > 0);
            assertTrue(ex.getSuppressed()[0] instanceof IllegalStateException);
        } finally {
            final SqlTransaction inner = SqlTransaction.getTransaction(dataSource, SqlTransaction.CreatedBy.JDBC_UTIL);
            if (inner != null) {
                inner.rollbackIfNotCommitted();
            }
        }
    }

    @SuppressWarnings("unchecked")
    private static void clearThreadTransactionMap() throws Exception {
        final Field mapField = SqlTransaction.class.getDeclaredField("threadTransactionMap");
        mapField.setAccessible(true);
        ((java.util.Map<String, SqlTransaction>) mapField.get(null)).clear();
    }

    @Test
    public void testResetConnectionContinuesAfterSetAutoCommitFails() throws SQLException {
        when(dataSource.getConnection()).thenReturn(connection);
        when(connection.getAutoCommit()).thenReturn(true);
        when(connection.getTransactionIsolation()).thenReturn(Connection.TRANSACTION_READ_COMMITTED);

        final SQLException autoCommitEx = new SQLException("autoCommit reset failed");
        doThrow(autoCommitEx).when(connection).setAutoCommit(true);

        final SqlTransaction tx = new SqlTransaction(dataSource, connection, IsolationLevel.DEFAULT, SqlTransaction.CreatedBy.JDBC_UTIL, true);
        tx.incrementAndGetRef(IsolationLevel.DEFAULT, false);

        tx.rollback();
        verify(connection).setAutoCommit(true);
        verify(connection).setTransactionIsolation(Connection.TRANSACTION_READ_COMMITTED);
        verify(connection).close();
    }

    @Test
    public void testResetConnectionContinuesAfterSetIsolationFails() throws SQLException {
        when(dataSource.getConnection()).thenReturn(connection);
        when(connection.getAutoCommit()).thenReturn(true);
        when(connection.getTransactionIsolation()).thenReturn(Connection.TRANSACTION_READ_COMMITTED);

        final SQLException isoEx = new SQLException("isolation reset failed");
        doThrow(isoEx).when(connection).setTransactionIsolation(Mockito.anyInt());

        final SqlTransaction tx = new SqlTransaction(dataSource, connection, IsolationLevel.DEFAULT, SqlTransaction.CreatedBy.JDBC_UTIL, true);
        tx.incrementAndGetRef(IsolationLevel.DEFAULT, false);

        tx.rollback();
        verify(connection).setAutoCommit(true);
        verify(connection).close();
    }

    @Test
    public void testDecrementAndGetRefKeepsStacksSymmetricOnIsolationFailure() throws Exception {
        when(dataSource.getConnection()).thenReturn(connection);
        when(connection.getAutoCommit()).thenReturn(true);
        when(connection.getTransactionIsolation()).thenReturn(Connection.TRANSACTION_READ_COMMITTED);

        final SqlTransaction tx = new SqlTransaction(dataSource, connection, IsolationLevel.READ_COMMITTED, SqlTransaction.CreatedBy.JDBC_UTIL, true);

        // First increment: refCount becomes 1, no stack push (initial scope).
        tx.incrementAndGetRef(IsolationLevel.READ_COMMITTED, false);
        // Second increment: refCount becomes 2; pushes the previous (isolation, forUpdateOnly)=(READ_COMMITTED,false) onto both stacks.
        tx.incrementAndGetRef(IsolationLevel.SERIALIZABLE, true);

        final Field isolationStackField = SqlTransaction.class.getDeclaredField("_isolationLevelStack");
        isolationStackField.setAccessible(true);
        final Field forUpdateStackField = SqlTransaction.class.getDeclaredField("_isForUpdateOnlyStack");
        forUpdateStackField.setAccessible(true);

        final java.util.Deque<?> isolationStack = (java.util.Deque<?>) isolationStackField.get(tx);
        final java.util.Deque<?> forUpdateStack = (java.util.Deque<?>) forUpdateStackField.get(tx);
        assertEquals(1, isolationStack.size());
        assertEquals(1, forUpdateStack.size());

        // Make the inner-scope isolation restore fail to trigger the catch path.
        Mockito.reset(connection);
        when(connection.getAutoCommit()).thenReturn(true);
        when(connection.getTransactionIsolation()).thenReturn(Connection.TRANSACTION_READ_COMMITTED);
        doThrow(new SQLException("isolation restore failed")).when(connection).setTransactionIsolation(Mockito.anyInt());

        assertThrows(UncheckedSQLException.class, tx::decrementAndGetRef);

        // Stacks must remain symmetric (same size) so subsequent decrements stay consistent.
        assertEquals(isolationStack.size(), forUpdateStack.size(),
                "_isolationLevelStack and _isForUpdateOnlyStack must remain the same size after restore failure");
    }

    // ---- Failure-during-beginTransaction must restore the connection's original state ----
    // Regression: SqlTransaction's constructor mutates the connection (autoCommit=false, then setTransactionIsolation)
    // BEFORE the SqlTransaction is published. If either the constructor's isolation change or the subsequent
    // incrementAndGetRef threw, the partially-mutated connection used to be released back to the pool dirty.
    // The fix: (1) make the constructor's mutations atomic (restore-on-failure); (2) on a post-construction
    // failure in JdbcUtil.beginTransaction, call SqlTransaction.resetAndCloseConnection to restore before release.

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
    public void testBeginTransaction_PostConstructionFailure_RestoresConnectionState() throws SQLException {
        // Constructor's setTransactionIsolation succeeds; the second call (from incrementAndGetRef) throws.
        final int serializable = IsolationLevel.SERIALIZABLE.intValue();
        doThrow(new SQLException("transient")).doNothing().when(connection).setTransactionIsolation(serializable);

        // First call (constructor) is rigged to throw to force the failure path AFTER the constructor; rearrange so
        // that the constructor's isolation set succeeds and the second one fails. Mockito doAnswer chains by order:
        Mockito.reset(connection);
        when(connection.getAutoCommit()).thenReturn(true);
        when(connection.getTransactionIsolation()).thenReturn(Connection.TRANSACTION_READ_COMMITTED);
        doNothing().doThrow(new SQLException("transient")).when(connection).setTransactionIsolation(serializable);

        assertThrows(UncheckedSQLException.class, () -> JdbcUtil.beginTransaction(dataSource, IsolationLevel.SERIALIZABLE));

        // resetAndCloseConnection (invoked from JdbcUtil.beginTransaction's failure path) must have restored
        // both autoCommit and the original isolation level before the connection went back to the pool.
        verify(connection).setAutoCommit(true);
        verify(connection).setTransactionIsolation(Connection.TRANSACTION_READ_COMMITTED);
    }

    // Regression: incrementAndGetRef on a NESTED scope pre-fix called conn.setTransactionIsolation
    // BEFORE pushing recovery state onto the isolation/forUpdateOnly stacks. If the JDBC call
    // failed, the outer scope's recovery state was never preserved (no push happened on this
    // entry, so a subsequent commit/rollback would have nothing to restore from). Fix pushes
    // first, then mutates the connection, and pops back the pushed values on failure.
    @Test
    public void testIncrementAndGetRef_NestedFailure_RestoresStacksAndRefcount() throws Exception {
        final SqlTransaction outer = JdbcUtil.beginTransaction(dataSource, IsolationLevel.READ_COMMITTED);

        // Outer scope: refCount=1, _isolationLevel=READ_COMMITTED. Now arrange that the NESTED
        // increment's setTransactionIsolation throws — pre-fix this would push nothing AND throw
        // before _refCount.incrementAndGet; post-fix the push-then-pop pattern leaves refCount and
        // stacks identical to their pre-call state.
        doThrow(new SQLException("nested-isolation-fail")).when(connection).setTransactionIsolation(IsolationLevel.SERIALIZABLE.intValue());

        assertThrows(UncheckedSQLException.class, () -> outer.incrementAndGetRef(IsolationLevel.SERIALIZABLE, false));

        // Outer scope must remain fully intact:
        assertEquals(IsolationLevel.READ_COMMITTED, outer.isolationLevel());
        // RefCount should still be 1 (incrementAndGet never ran).
        final Field refCountField = SqlTransaction.class.getDeclaredField("_refCount");
        refCountField.setAccessible(true);
        assertEquals(1, ((AtomicInteger) refCountField.get(outer)).get());

        // Outer commit must succeed cleanly without "isolation stack empty" errors.
        Mockito.reset(connection);
        when(connection.getAutoCommit()).thenReturn(true);
        when(connection.getTransactionIsolation()).thenReturn(Connection.TRANSACTION_READ_COMMITTED);
        outer.commit();
    }

    // Regression: decrementAndGetRef failure path used to push the popped values back to the
    // isolation/forUpdateOnly stacks but did NOT restore the _isolationLevel/_isForUpdateOnly
    // FIELDS. After a setTransactionIsolation failure, the transaction was left in an inconsistent
    // state — fields reflected the outer scope, stacks held the outer scope's values, so the next
    // nested entry would push the OUTER value (not the original NESTED value) onto the stack,
    // causing wrong unwind behavior later.
    @Test
    public void testDecrementAndGetRef_RestoreFailure_RestoresBothStacksAndFields() throws Exception {
        final SqlTransaction tran = JdbcUtil.beginTransaction(dataSource, IsolationLevel.READ_COMMITTED);
        tran.incrementAndGetRef(IsolationLevel.SERIALIZABLE, true); // refCount 1 -> 2; stack pushes READ_COMMITTED/false

        // On decrement, the code pops READ_COMMITTED from the stack and tries to restore it on the
        // connection. Make THAT setTransactionIsolation throw.
        doThrow(new SQLException("restore-fail")).when(connection).setTransactionIsolation(Connection.TRANSACTION_READ_COMMITTED);

        assertThrows(UncheckedSQLException.class, tran::decrementAndGetRef);

        // After the failure, the FIELD should be restored to its pre-pop value (SERIALIZABLE),
        // matching the stack which has been re-pushed with READ_COMMITTED. Pre-fix the field was
        // left at READ_COMMITTED (stale post-pop value) — observable inconsistency.
        assertEquals(IsolationLevel.SERIALIZABLE, tran.isolationLevel());

        // Stack should hold the re-pushed value.
        final Field stackField = SqlTransaction.class.getDeclaredField("_isolationLevelStack");
        stackField.setAccessible(true);
        final java.util.Deque<?> stack = (java.util.Deque<?>) stackField.get(tran);
        assertEquals(1, stack.size());
        assertEquals(IsolationLevel.READ_COMMITTED, stack.peek());
    }

    // Regression: executeRollback's finally block called actionAfterRollback.run() unguarded.
    // If the action threw a RuntimeException AFTER a rollback that itself failed, the action's
    // exception escaped the finally and replaced the primary UncheckedSQLException from the
    // failed rollback — the caller would never see the underlying SQL error. Fix wraps the
    // action in a try/catch that suppresses+logs action exceptions when a primary rollback
    // failure is propagating, and re-throws otherwise.
    @Test
    public void testRollback_ActionAfterRollbackDoesNotMaskPrimaryRollbackFailure() throws SQLException {
        final SqlTransaction tran = JdbcUtil.beginTransaction(dataSource, IsolationLevel.READ_COMMITTED);
        // Force the rollback itself to fail.
        doThrow(new SQLException("rollback-boom")).when(connection).rollback();

        final UncheckedSQLException ex = assertThrows(UncheckedSQLException.class, () -> tran.rollback(() -> {
            throw new RuntimeException("post-rollback-action-boom");
        }));

        // Primary cause must be the rollback SQLException, not the post-action exception.
        assertNotNull(ex.getCause());
        assertEquals("rollback-boom", ex.getCause().getMessage());
    }

    @Test
    public void testRollback_ActionAfterRollbackPropagatesWhenRollbackSucceeded() throws SQLException {
        final SqlTransaction tran = JdbcUtil.beginTransaction(dataSource, IsolationLevel.READ_COMMITTED);
        // rollback succeeds; only the action throws.
        doNothing().when(connection).rollback();

        // With no primary rollback failure, the action's exception is the only signal — it must
        // propagate so the caller knows the action failed.
        final RuntimeException ex = assertThrows(RuntimeException.class, () -> tran.rollback(() -> {
            throw new RuntimeException("post-rollback-action-boom");
        }));
        assertEquals("post-rollback-action-boom", ex.getMessage());
    }
}
