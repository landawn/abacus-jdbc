package com.landawn.abacus.jdbc;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
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
import org.mockito.MockitoAnnotations;

import com.landawn.abacus.TestBase;
import com.landawn.abacus.exception.UncheckedSQLException;
import com.landawn.abacus.util.Throwables;

public class SQLTransactionTest extends TestBase {

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
    public void testConstructor() throws SQLException {
        SQLTransaction transaction = JdbcUtil.beginTransaction(dataSource, IsolationLevel.READ_COMMITTED);

        assertNotNull(transaction.id());
        assertEquals(connection, transaction.connection());
        assertEquals(IsolationLevel.READ_COMMITTED, transaction.isolationLevel());
        assertEquals(Transaction.Status.ACTIVE, transaction.status());
        assertTrue(transaction.isActive());

        verify(connection).setAutoCommit(false);
    }

    @Test
    public void testCommit() throws SQLException {
        SQLTransaction transaction = JdbcUtil.beginTransaction(dataSource, IsolationLevel.READ_COMMITTED);

        transaction.commit();

        assertEquals(Transaction.Status.COMMITTED, transaction.status());
        assertFalse(transaction.isActive());
        verify(connection).commit();
        verify(connection).setAutoCommit(true);
    }

    @Test
    public void testCommitFailure() throws SQLException {
        SQLTransaction transaction = JdbcUtil.beginTransaction(dataSource, IsolationLevel.READ_COMMITTED);

        doThrow(new SQLException("Commit failed")).when(connection).commit();

        assertThrows(UncheckedSQLException.class, () -> transaction.commit());
        assertEquals(Transaction.Status.ROLLED_BACK, transaction.status());
        verify(connection).rollback();
    }

    @Test
    public void testRollback() throws SQLException {
        SQLTransaction transaction = JdbcUtil.beginTransaction(dataSource, IsolationLevel.READ_COMMITTED);

        transaction.rollback();

        assertEquals(Transaction.Status.ROLLED_BACK, transaction.status());
        assertFalse(transaction.isActive());
        verify(connection).rollback();
    }

    @Test
    public void testRollbackIfNotCommitted() throws SQLException {
        SQLTransaction transaction = JdbcUtil.beginTransaction(dataSource, IsolationLevel.READ_COMMITTED);

        transaction.rollbackIfNotCommitted();

        assertEquals(Transaction.Status.ROLLED_BACK, transaction.status());
        verify(connection).rollback();
    }

    @Test
    public void testRollbackIfNotCommittedAfterCommit() throws SQLException {
        SQLTransaction transaction = JdbcUtil.beginTransaction(dataSource, IsolationLevel.READ_COMMITTED);

        transaction.commit();
        transaction.rollbackIfNotCommitted();

        assertEquals(Transaction.Status.COMMITTED, transaction.status());
        verify(connection, times(1)).commit();
        verify(connection, never()).rollback();
    }

    @Test
    public void testRunNotInMe() throws Exception {
        SQLTransaction transaction = JdbcUtil.beginTransaction(dataSource, IsolationLevel.READ_COMMITTED);

        final boolean[] executed = { false };
        Throwables.Runnable<Exception> runnable = () -> executed[0] = true;

        transaction.runNotInMe(runnable);

        assertTrue(executed[0]);
    }

    @Test
    public void testCallNotInMe() throws Exception {
        SQLTransaction transaction = JdbcUtil.beginTransaction(dataSource, IsolationLevel.READ_COMMITTED);

        Throwables.Callable<String, Exception> callable = () -> "test result";

        String result = transaction.callNotInMe(callable);

        assertEquals("test result", result);
    }

    @Test
    public void testClose() throws SQLException {
        SQLTransaction transaction = JdbcUtil.beginTransaction(dataSource, IsolationLevel.READ_COMMITTED);

        transaction.close();

        assertEquals(Transaction.Status.ROLLED_BACK, transaction.status());
        verify(connection).rollback();
    }

    @Test
    public void testIncrementAndGetRef() throws SQLException {
        SQLTransaction transaction = JdbcUtil.beginTransaction(dataSource, IsolationLevel.READ_COMMITTED);

        int refCount = transaction.incrementAndGetRef(IsolationLevel.SERIALIZABLE, false);

        assertEquals(2, refCount);
        assertEquals(IsolationLevel.SERIALIZABLE, transaction.isolationLevel());
        verify(connection).setTransactionIsolation(Connection.TRANSACTION_SERIALIZABLE);
    }

    @Test
    public void testIsForUpdateOnly() throws SQLException {
        SQLTransaction transaction = JdbcUtil.beginTransaction(dataSource, IsolationLevel.READ_COMMITTED);

        assertFalse(transaction.isForUpdateOnly());

        transaction.incrementAndGetRef(IsolationLevel.READ_COMMITTED, true);
        assertTrue(transaction.isForUpdateOnly());
    }

    @Test
    public void testGetTransactionId() {
        String id = SQLTransaction.getTransactionId(dataSource, SQLTransaction.CreatedBy.JDBC_UTIL);
        assertNotNull(id);
        assertTrue(id.contains(String.valueOf(System.identityHashCode(dataSource))));
        assertTrue(id.contains(String.valueOf(Thread.currentThread().getId())));
    }

    @Test
    public void testEquals() throws SQLException {
        SQLTransaction transaction1 = JdbcUtil.beginTransaction(dataSource, IsolationLevel.READ_COMMITTED);
        SQLTransaction transaction2 = JdbcUtil.beginTransaction(dataSource, IsolationLevel.READ_COMMITTED);

        assertEquals(transaction1, transaction2);
        assertNotEquals(transaction1, null);
        assertNotEquals(transaction1, "not a transaction");
    }

    @Test
    public void testHashCode() throws SQLException {
        SQLTransaction transaction = JdbcUtil.beginTransaction(dataSource, IsolationLevel.READ_COMMITTED);

        assertEquals(transaction.id().hashCode(), transaction.hashCode());
    }

    @Test
    public void testToString() throws SQLException {
        SQLTransaction transaction = JdbcUtil.beginTransaction(dataSource, IsolationLevel.READ_COMMITTED);

        String toString = transaction.toString();
        assertNotNull(toString);
        assertTrue(toString.contains("SQLTransaction"));
        assertTrue(toString.contains("id="));
    }
}