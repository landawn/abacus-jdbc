package com.landawn.abacus.jdbc;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.sql.Connection;
import java.sql.SQLException;

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
}
