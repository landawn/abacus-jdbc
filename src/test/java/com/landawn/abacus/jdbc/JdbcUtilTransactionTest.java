package com.landawn.abacus.jdbc;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import com.landawn.abacus.exception.UncheckedSQLException;

class JdbcUtilTransactionTest extends JdbcUtilTestSupport {

    @Test
    public void testTableExists_FallbackProbeSuccessInTransaction_ReleasesSavepoint() throws SQLException {
        final java.sql.Savepoint savepoint = mock(java.sql.Savepoint.class);
        when(mockDatabaseMetaData.getDatabaseProductName()).thenReturn("PostgreSQL");
        when(mockConnection.getAutoCommit()).thenReturn(false);
        when(mockConnection.setSavepoint()).thenReturn(savepoint);

        assertTrue(JdbcUtil.tableExists(mockConnection, "present_tbl"));

        verify(mockConnection).prepareStatement("SELECT 1 FROM present_tbl WHERE 1 > 2");
        verify(mockConnection).releaseSavepoint(savepoint);
        verify(mockConnection, never()).rollback(savepoint);
    }

    // A driver without savepoint support: the probe still runs (without a savepoint). A present table is reported; a missing
    // one is no longer answered with false, since the failed probe has left the open PostgreSQL transaction aborted (25P02):
    // the "table not found" failure is propagated instead (see JdbcUtilMetadataTest for the auto-commit case).
    @Test
    public void testTableExists_FallbackProbeInTransaction_SavepointsUnsupported_ProbesWithoutSavepoint() throws SQLException {
        final SQLException notFound = new SQLException("relation \"missing_tbl\" does not exist", "42P01");
        when(mockDatabaseMetaData.getDatabaseProductName()).thenReturn("PostgreSQL");
        when(mockConnection.getAutoCommit()).thenReturn(false);
        when(mockConnection.setSavepoint()).thenThrow(new java.sql.SQLFeatureNotSupportedException("savepoints"));
        when(mockConnection.prepareStatement("SELECT 1 FROM missing_tbl WHERE 1 > 2")).thenThrow(notFound);

        assertTrue(JdbcUtil.tableExists(mockConnection, "present_tbl"));
        assertSame(notFound, assertThrows(UncheckedSQLException.class, () -> JdbcUtil.tableExists(mockConnection, "missing_tbl")).getCause());

        verify(mockConnection, never()).rollback(org.mockito.ArgumentMatchers.any(java.sql.Savepoint.class));
        verify(mockConnection, never()).releaseSavepoint(org.mockito.ArgumentMatchers.any(java.sql.Savepoint.class));
    }

    // Databases that roll back only the failed statement (MySQL, Oracle, SQL Server, ...) need no savepoint: the
    // "table not found" probe failure leaves the caller's transaction usable.
    @Test
    public void testTableExists_FallbackProbeInTransaction_NonPostgreSQL_NoSavepoint() throws SQLException {
        when(mockConnection.getAutoCommit()).thenReturn(false);
        when(mockConnection.prepareStatement("SELECT 1 FROM missing_tbl WHERE 1 > 2"))
                .thenThrow(new SQLException("Table 'missing_tbl' doesn't exist", "42S02"));

        for (final String productName : new String[] { "MySQL", "Oracle", "Microsoft SQL Server" }) {
            when(mockDatabaseMetaData.getDatabaseProductName()).thenReturn(productName);

            assertFalse(JdbcUtil.tableExists(mockConnection, "missing_tbl"), productName);
            assertTrue(JdbcUtil.tableExists(mockConnection, "present_tbl"), productName);
        }

        verify(mockConnection, never()).setSavepoint();
        verify(mockConnection, never()).rollback(org.mockito.ArgumentMatchers.any(java.sql.Savepoint.class));
        verify(mockConnection, never()).releaseSavepoint(org.mockito.ArgumentMatchers.any(java.sql.Savepoint.class));
    }

    // BUG FIX: in a transaction (auto-commit off) the tableExists fallback probe must run under a savepoint and roll
    // back to it when the probe fails. On PostgreSQL an expected "relation does not exist" error otherwise aborts the
    // caller's whole transaction ("current transaction is aborted") although tableExists just returns false.
    @Test
    public void testTableExists_FallbackProbeFailureInTransaction_RollsBackToSavepoint() throws SQLException {
        final java.sql.Savepoint savepoint = mock(java.sql.Savepoint.class);
        when(mockDatabaseMetaData.getDatabaseProductName()).thenReturn("PostgreSQL");
        when(mockConnection.getAutoCommit()).thenReturn(false);
        when(mockConnection.setSavepoint()).thenReturn(savepoint);
        when(mockConnection.prepareStatement("SELECT 1 FROM missing_tbl WHERE 1 > 2"))
                .thenThrow(new SQLException("relation \"missing_tbl\" does not exist", "42P01"));

        assertFalse(JdbcUtil.tableExists(mockConnection, "missing_tbl"));

        verify(mockConnection).rollback(savepoint);
        verify(mockConnection).releaseSavepoint(savepoint);
    }

    // A probe failure that is not "table not found" is still propagated, after the savepoint restored the transaction;
    // a failing rollback is attached to it as suppressed.
    @Test
    public void testTableExists_FallbackProbeOtherErrorInTransaction_RollsBackAndPropagates() throws SQLException {
        final java.sql.Savepoint savepoint = mock(java.sql.Savepoint.class);
        final SQLException probeFailure = new SQLException("could not read block 0 in file", "XX001");
        final SQLException rollbackFailure = new SQLException("rollback failed");
        when(mockDatabaseMetaData.getDatabaseProductName()).thenReturn("PostgreSQL");
        when(mockConnection.getAutoCommit()).thenReturn(false);
        when(mockConnection.setSavepoint()).thenReturn(savepoint);
        when(mockConnection.prepareStatement("SELECT 1 FROM secret_tbl WHERE 1 > 2")).thenThrow(probeFailure);
        doThrow(rollbackFailure).when(mockConnection).rollback(savepoint);

        final UncheckedSQLException thrown = assertThrows(UncheckedSQLException.class, () -> JdbcUtil.tableExists(mockConnection, "secret_tbl"));

        assertSame(probeFailure, thrown.getCause());
        assertArrayEquals(new Throwable[] { rollbackFailure }, probeFailure.getSuppressed());
        verify(mockConnection).rollback(savepoint);
        verify(mockConnection).releaseSavepoint(savepoint);
    }

    // PostgreSQL-compatible products reporting their own product name are also probed under a savepoint.
    @Test
    public void testTableExists_FallbackProbeFailureInTransaction_Redshift_RollsBackToSavepoint() throws SQLException {
        final java.sql.Savepoint savepoint = mock(java.sql.Savepoint.class);
        when(mockDatabaseMetaData.getDatabaseProductName()).thenReturn("Redshift");
        when(mockConnection.getAutoCommit()).thenReturn(false);
        when(mockConnection.setSavepoint()).thenReturn(savepoint);
        when(mockConnection.prepareStatement("SELECT 1 FROM missing_tbl WHERE 1 > 2"))
                .thenThrow(new SQLException("relation \"missing_tbl\" does not exist", "42P01"));

        assertFalse(JdbcUtil.tableExists(mockConnection, "missing_tbl"));

        verify(mockConnection).rollback(savepoint);
        verify(mockConnection).releaseSavepoint(savepoint);
    }

    @Test
    public void testIsInTransaction() throws SQLException {
        assertFalse(JdbcUtil.isInTransaction(mockDataSource));

        SqlTransaction tran = JdbcUtil.beginTransaction(mockDataSource);
        assertTrue(JdbcUtil.isInTransaction(mockDataSource));
        tran.rollbackIfNotCommitted();
    }

    @Test
    @DisplayName("isInTransaction should not propagate LinkageError when Spring is on classpath but mismatched")
    public void testIsInTransactionToleratesLinkageError() throws Exception {
        // The Spring transaction check should catch any LinkageError subtype (NoClassDefFoundError, NoSuchMethodError, etc.) —
        // not just NoClassDefFoundError — so that an absent/mismatched Spring on the classpath does not crash callers. The check
        // reads Spring's thread-bound transaction state only: it acquires no connection. The ownership lookup (getResource) runs
        // first, and the completion check only for a holder owned by a transaction.
        assertTrue(JdbcUtil.isInSpring, "Spring is on the test class path");

        try (org.mockito.MockedStatic<org.springframework.transaction.support.TransactionSynchronizationManager> synchronizationManager = org.mockito.Mockito
                .mockStatic(org.springframework.transaction.support.TransactionSynchronizationManager.class)) {
            synchronizationManager
                    .when(() -> org.springframework.transaction.support.TransactionSynchronizationManager.getResource(org.mockito.ArgumentMatchers.any()))
                    .thenThrow(new NoSuchMethodError("incompatible Spring API"));

            assertFalse(JdbcUtil.isInTransaction(mockDataSource));
            verify(mockDataSource, never()).getConnection();
        }

        final java.sql.Connection springConnection = mock(java.sql.Connection.class);
        final org.springframework.jdbc.datasource.ConnectionHolder holder = new org.springframework.jdbc.datasource.ConnectionHolder(() -> springConnection);

        try (org.mockito.MockedStatic<org.springframework.transaction.support.TransactionSynchronizationManager> synchronizationManager = org.mockito.Mockito
                .mockStatic(org.springframework.transaction.support.TransactionSynchronizationManager.class)) {
            synchronizationManager
                    .when(() -> org.springframework.transaction.support.TransactionSynchronizationManager.getResource(org.mockito.ArgumentMatchers.any()))
                    .thenReturn(holder);
            synchronizationManager.when(org.springframework.transaction.support.TransactionSynchronizationManager::isSynchronizationActive)
                    .thenThrow(new NoSuchMethodError("incompatible Spring API"));

            assertTrue(JdbcUtil.isInTransaction(mockDataSource), "an owned holder, whose completion can't be checked");
        }

        // Round 15 review: a LinkageError from the driver, reading the auto-commit mode of the bound connection, was taken for one of
        // Spring's own and answered "no transaction" (MANDATORY failed, NEVER ran). It propagates, as from getConnection.
        final NoClassDefFoundError driverFailure = new NoClassDefFoundError("a driver class");
        final java.sql.Connection brokenConnection = mock(java.sql.Connection.class);
        when(brokenConnection.getAutoCommit()).thenThrow(driverFailure);
        final org.springframework.jdbc.datasource.ConnectionHolder brokenHolder = new org.springframework.jdbc.datasource.ConnectionHolder(
                () -> brokenConnection);

        try (org.mockito.MockedStatic<org.springframework.transaction.support.TransactionSynchronizationManager> synchronizationManager = org.mockito.Mockito
                .mockStatic(org.springframework.transaction.support.TransactionSynchronizationManager.class)) {
            synchronizationManager
                    .when(() -> org.springframework.transaction.support.TransactionSynchronizationManager.getResource(org.mockito.ArgumentMatchers.any()))
                    .thenReturn(brokenHolder);

            assertSame(driverFailure, assertThrows(NoClassDefFoundError.class, () -> JdbcUtil.isInTransaction(mockDataSource)));
        }

        // External review (P1, P2): the same for a LinkageError from the holder's connection handle (an ORM transaction manager
        // hands out its connection through the provider's code). Caught as Spring's own, it answered "no transaction", and a
        // transaction begun then took Spring's still-bound connection as one of its own: its commit committed the Spring
        // transaction's earlier work. It propagates now, before a connection is taken.
        final java.sql.Connection ormConnection = mock(java.sql.Connection.class);
        final java.util.concurrent.atomic.AtomicInteger handleCalls = new java.util.concurrent.atomic.AtomicInteger();
        final NoClassDefFoundError providerFailure = new NoClassDefFoundError("a provider class");
        final org.springframework.jdbc.datasource.ConnectionHolder ormHolder = new org.springframework.jdbc.datasource.ConnectionHolder(() -> {
            if (handleCalls.getAndIncrement() == 0) {
                throw providerFailure; // fails once
            }

            return ormConnection;
        });

        try (org.mockito.MockedStatic<org.springframework.transaction.support.TransactionSynchronizationManager> synchronizationManager = org.mockito.Mockito
                .mockStatic(org.springframework.transaction.support.TransactionSynchronizationManager.class)) {
            synchronizationManager
                    .when(() -> org.springframework.transaction.support.TransactionSynchronizationManager.getResource(org.mockito.ArgumentMatchers.any()))
                    .thenReturn(ormHolder);

            assertSame(providerFailure, assertThrows(NoClassDefFoundError.class, () -> JdbcUtil.beginTransaction(mockDataSource)));
            verify(ormConnection, never()).commit();
            verify(mockDataSource, never()).getConnection();
            assertFalse(
                    JdbcUtil.isInTransaction(mockDataSource) && TransactionContext.getTransaction(mockDataSource, SqlTransaction.CreatedBy.JDBC_UTIL) != null,
                    "no transaction was begun");
        }
    }

    @Test
    public void testBeginTransaction() throws SQLException {
        SqlTransaction tran = JdbcUtil.beginTransaction(mockDataSource);
        assertNotNull(tran);
        assertTrue(tran.isActive(), "newly begun transaction should be active");
        // The data source should now be marked as in-transaction
        assertTrue(JdbcUtil.isInTransaction(mockDataSource));
        tran.rollbackIfNotCommitted();
    }

    @Test
    public void testBeginTransactionWithIsolation() throws SQLException {
        SqlTransaction tran = JdbcUtil.beginTransaction(mockDataSource, IsolationLevel.READ_COMMITTED);
        assertNotNull(tran);
        assertTrue(tran.isActive(), "newly begun transaction should be active");
        assertEquals(IsolationLevel.READ_COMMITTED, tran.isolationLevel(), "isolation level should match the requested level");
        tran.rollbackIfNotCommitted();
    }

    @Test
    public void testBeginTransactionRejectsUnsupportedIsolationBeforeAcquiringConnection() {
        assertThrows(IllegalArgumentException.class, () -> JdbcUtil.beginTransaction(mockDataSource, IsolationLevel.NONE));
        verifyNoInteractions(mockDataSource);
    }

    @Test
    public void testCallInTransaction() throws SQLException {
        String result = JdbcUtil.callInTransaction(mockDataSource, () -> "test");
        assertEquals("test", result);
    }

    @Test
    public void testCallInTransactionWithConnection() throws SQLException {
        String result = JdbcUtil.callInTransaction(mockDataSource, conn -> {
            assertNotNull(conn);
            return "test";
        });
        assertEquals("test", result);
    }

    @ParameterizedTest
    @ValueSource(booleans = { false, true })
    void connectionCallbackFailureSurvivesRollbackFailure(final boolean returnsValue) throws SQLException {
        final IllegalStateException callbackFailure = new IllegalStateException("business failure");
        final SQLException rollbackFailure = new SQLException("rollback failed");
        doThrow(rollbackFailure).when(mockConnection).rollback();

        final IllegalStateException thrown = assertThrows(IllegalStateException.class, () -> {
            if (returnsValue) {
                JdbcUtil.callInTransaction(mockDataSource, connection -> {
                    assertSame(mockConnection, connection);
                    throw callbackFailure;
                });
            } else {
                JdbcUtil.runInTransaction(mockDataSource, connection -> {
                    assertSame(mockConnection, connection);
                    throw callbackFailure;
                });
            }
        });

        assertSame(callbackFailure, thrown);
        assertEquals(1, thrown.getSuppressed().length);
        assertSame(rollbackFailure, thrown.getSuppressed()[0].getCause());
        verify(mockConnection).rollback();
        verify(mockConnection, never()).commit();
        verify(mockConnection).close();
    }

    @Test
    public void testCallInTransactionPreservesCallbackFailureWhenRollbackFails() throws SQLException {
        final IllegalStateException callbackFailure = new IllegalStateException("business failure");
        doThrow(new SQLException("rollback failure")).when(mockConnection).rollback();

        final IllegalStateException thrown = assertThrows(IllegalStateException.class, () -> JdbcUtil.callInTransaction(mockDataSource, () -> {
            throw callbackFailure;
        }));

        assertTrue(thrown == callbackFailure);
        assertEquals(1, thrown.getSuppressed().length);
        assertTrue(thrown.getSuppressed()[0] instanceof UncheckedSQLException);
        assertEquals("rollback failure", thrown.getSuppressed()[0].getCause().getMessage());
    }

    @Test
    @DisplayName("transaction callback preserves a failure object reused by rollback")
    public void testTransactionCommandDoesNotSelfSuppressReusedRollbackFailure() throws SQLException {
        final IllegalStateException sharedFailure = new IllegalStateException("shared command/rollback failure");
        doThrow(sharedFailure).when(mockConnection).rollback();

        final IllegalStateException thrown = assertThrows(IllegalStateException.class, () -> JdbcUtil.callInTransaction(mockDataSource, () -> {
            throw sharedFailure;
        }));

        assertSame(sharedFailure, thrown);
        assertEquals(0, thrown.getSuppressed().length);
    }

    @Test
    public void testRunInTransaction() throws SQLException {
        final boolean[] executed = { false };
        JdbcUtil.runInTransaction(mockDataSource, () -> {
            executed[0] = true;
        });
        assertTrue(executed[0]);
    }

    @Test
    public void testRunInTransactionWithConnection() throws SQLException {
        final boolean[] executed = { false };
        JdbcUtil.runInTransaction(mockDataSource, conn -> {
            assertNotNull(conn);
            executed[0] = true;
        });
        assertTrue(executed[0]);
    }

    @Test
    public void testCallOutsideTransaction() throws SQLException {
        String result = JdbcUtil.callOutsideTransaction(mockDataSource, () -> "test");
        assertEquals("test", result);
    }

    @Test
    public void testCallOutsideTransactionWithDataSource() throws SQLException {
        String result = JdbcUtil.callOutsideTransaction(mockDataSource, ds -> {
            assertNotNull(ds);
            return "test";
        });
        assertEquals("test", result);
    }

    // Test callOutsideTransaction when inside a transaction - runs outside the transaction
    @Test
    public void testCallOutsideTransaction_WhileInTransaction() throws SQLException {
        SqlTransaction tran = JdbcUtil.beginTransaction(mockDataSource);
        try {
            // The call should execute outside the active transaction
            String result = JdbcUtil.callOutsideTransaction(mockDataSource, () -> "outsideResult");
            assertEquals("outsideResult", result);
        } finally {
            tran.rollbackIfNotCommitted();
        }
    }

    @Test
    public void testCallOutsideTransactionWithDS_WhileInTransaction() throws SQLException {
        SqlTransaction tran = JdbcUtil.beginTransaction(mockDataSource);
        try {
            String result = JdbcUtil.callOutsideTransaction(mockDataSource, ds -> {
                assertNotNull(ds);
                return "dsResult";
            });
            assertEquals("dsResult", result);
        } finally {
            tran.rollbackIfNotCommitted();
        }
    }

    @Test
    public void testRunOutsideTransaction() throws SQLException {
        final boolean[] executed = { false };
        JdbcUtil.runOutsideTransaction(mockDataSource, () -> {
            executed[0] = true;
        });
        assertTrue(executed[0]);
    }

    @Test
    public void testRunOutsideTransactionWithDataSource() throws SQLException {
        final boolean[] executed = { false };
        JdbcUtil.runOutsideTransaction(mockDataSource, ds -> {
            assertNotNull(ds);
            executed[0] = true;
        });
        assertTrue(executed[0]);
    }

    @Test
    public void testRunOutsideTransaction_WhileInTransaction() throws SQLException {
        SqlTransaction tran = JdbcUtil.beginTransaction(mockDataSource);
        try {
            boolean[] ran = { false };
            JdbcUtil.runOutsideTransaction(mockDataSource, () -> ran[0] = true);
            assertTrue(ran[0]);
        } finally {
            tran.rollbackIfNotCommitted();
        }
    }

    @Test
    public void testRunOutsideTransactionWithDS_WhileInTransaction() throws SQLException {
        SqlTransaction tran = JdbcUtil.beginTransaction(mockDataSource);
        try {
            boolean[] ran = { false };
            JdbcUtil.runOutsideTransaction(mockDataSource, ds -> {
                assertNotNull(ds);
                ran[0] = true;
            });
            assertTrue(ran[0]);
        } finally {
            tran.rollbackIfNotCommitted();
        }
    }

    @Test
    public void testRunWithoutUsingSpringTransaction() {
        final boolean[] executed = { false };
        JdbcUtil.runIgnoringSpringTransaction(() -> {
            executed[0] = true;
        });
        assertTrue(executed[0]);
    }

    @Test
    public void testCallWithoutUsingSpringTransaction() {
        String result = JdbcUtil.callIgnoringSpringTransaction(() -> "test");
        assertEquals("test", result);
    }

    // Every statement joins the thread's transaction, a batch of a plain read included: it runs on the transaction's
    // connection. A for-update-only transaction used to run it on a connection of its own.
    @ParameterizedTest(name = "large={0}")
    @ValueSource(booleans = { false, true })
    public void testExecuteBatchUpdate_PlainReadJoinsTheTransaction(final boolean large) throws SQLException {
        final String sql = "SELECT id FROM account WHERE id = ?";
        final PreparedStatement readStatement = mock(PreparedStatement.class);
        when(mockConnection.prepareStatement(sql)).thenReturn(readStatement);
        when(readStatement.executeBatch()).thenReturn(new int[] { 1, 1 });
        when(readStatement.executeLargeBatch()).thenReturn(new long[] { 1, 1 });
        final List<Object[]> rows = List.of(new Object[] { 1 }, new Object[] { 2 });
        final SqlTransaction tran = JdbcUtil.beginTransaction(mockDataSource, IsolationLevel.READ_COMMITTED);

        try {
            assertEquals(2, large ? JdbcUtil.executeLargeBatchUpdate(mockDataSource, sql, rows) : JdbcUtil.executeBatchUpdate(mockDataSource, sql, rows));

            assertTrue(tran.isActive());
            verify(readStatement, times(2)).addBatch();
            verify(mockDataSource, times(1)).getConnection();
        } finally {
            tran.rollbackIfNotCommitted();
        }
    }

    // JU-C-5: markSpringTransactionRollbackOnly catches a LinkageError out of the Spring adapter (Spring's transaction classes
    // failing to load or link), logs it and rethrows it. Swallowed, the error made a rollback that marked nothing look complete:
    // the generated IDs were put back although Spring may commit the rows; propagated, SqlTransaction leaves the undo to
    // Spring's outcome. The adapter is a private nested class: mocked as a static through its reflected method.
    @Test
    public void testMarkSpringTransactionRollbackOnly_PropagatesALinkageErrorOutOfTheSpringAdapter() throws Exception {
        final Class<?> springRollbackActions = Class.forName("com.landawn.abacus.jdbc.JdbcUtil$SpringRollbackActions");
        final java.lang.reflect.Method markRollbackOnly = springRollbackActions.getDeclaredMethod("markRollbackOnly", Object.class);
        markRollbackOnly.setAccessible(true);
        final NoClassDefFoundError failure = new NoClassDefFoundError("org/springframework/transaction/support/TransactionSynchronizationManager");

        try (org.mockito.MockedStatic<?> adapter = org.mockito.Mockito.mockStatic(springRollbackActions)) {
            adapter.when(() -> markRollbackOnly.invoke(null, mockDataSource)).thenThrow(failure);

            assertSame(failure, assertThrows(NoClassDefFoundError.class, () -> JdbcUtil.markSpringTransactionRollbackOnly(mockDataSource)));
            adapter.verify(() -> markRollbackOnly.invoke(null, mockDataSource));
        }

        verifyNoInteractions(mockDataSource);
    }
}
