package com.landawn.abacus.jdbc;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.sql.CallableStatement;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

@Tag("2025")
class JdbcUtilParameterCleanupReviewTest {

    @Test
    void successfulQueryKeepsItsResultSetWhenParameterCleanupFailsUnchecked() throws SQLException {
        final PreparedStatement stmt = mock(PreparedStatement.class);
        final ResultSet driverResult = mock(ResultSet.class);
        when(stmt.executeQuery()).thenReturn(driverResult);
        when(driverResult.next()).thenReturn(true);
        when(driverResult.getInt(1)).thenReturn(42);
        doThrow(new IllegalStateException("clear failed")).when(stmt).clearParameters();

        try (ResultSet result = JdbcUtil.executeQuery(stmt)) {
            assertTrue(result.next());
            assertEquals(42, result.getInt(1));
        }

        verify(stmt).clearParameters();
        verify(driverResult).close();
    }

    @Test
    void executionFailuresSurviveUncheckedParameterCleanupForEveryOperation() throws SQLException {
        for (int operation = 0; operation < 4; operation++) {
            final PreparedStatement stmt = mock(PreparedStatement.class);
            final SQLException primary = new SQLException("execution failed");
            doThrow(primary).when(stmt).executeQuery();
            doThrow(primary).when(stmt).executeUpdate();
            doThrow(primary).when(stmt).executeLargeUpdate();
            doThrow(primary).when(stmt).execute();
            doThrow(new IllegalStateException("clear failed")).when(stmt).clearParameters();
            final int selectedOperation = operation;

            assertSame(primary, assertThrows(SQLException.class, () -> execute(stmt, selectedOperation)));
            verify(stmt).clearParameters();
        }
    }

    @Test
    void fatalParameterCleanupErrorsStillPropagate() throws SQLException {
        final PreparedStatement stmt = mock(PreparedStatement.class);
        final AssertionError failure = new AssertionError("fatal cleanup");
        doThrow(failure).when(stmt).clearParameters();

        assertSame(failure, assertThrows(AssertionError.class, () -> JdbcUtil.clearParameters(stmt)));
    }

    @Test
    void successfulUpdateAndExecuteResultsSurviveUncheckedParameterCleanup() throws SQLException {
        final PreparedStatement stmt = mock(PreparedStatement.class);
        final long largeCount = (long) Integer.MAX_VALUE + 1;
        when(stmt.executeUpdate()).thenReturn(7);
        when(stmt.executeLargeUpdate()).thenReturn(largeCount);
        when(stmt.execute()).thenReturn(true);
        doThrow(new IllegalStateException("clear failed")).when(stmt).clearParameters();

        assertEquals(7, JdbcUtil.executeUpdate(stmt));
        assertEquals(largeCount, JdbcUtil.executeLargeUpdate(stmt));
        assertTrue(JdbcUtil.execute(stmt));
        verify(stmt, never()).close();
    }

    @Test
    void uncheckedExecutionFailuresSurviveBestEffortParameterCleanup() throws SQLException {
        for (final Throwable primary : new Throwable[] { new IllegalStateException("execution failed"), new AssertionError("execution failed") }) {
            final PreparedStatement stmt = mock(PreparedStatement.class);
            doThrow(primary).when(stmt).execute();
            doThrow(new IllegalStateException("clear failed")).when(stmt).clearParameters();

            assertSame(primary, assertThrows(primary.getClass(), () -> JdbcUtil.execute(stmt)));
            verify(stmt).clearParameters();
        }
    }

    @Test
    void callableStatementsKeepTheirBindingsAndOutRegistrations() throws SQLException {
        final CallableStatement stmt = mock(CallableStatement.class);
        final ResultSet driverResult = mock(ResultSet.class);
        when(stmt.executeQuery()).thenReturn(driverResult);

        JdbcUtil.clearParameters(null);
        try (ResultSet result = JdbcUtil.executeQuery(stmt)) {
            JdbcUtil.executeUpdate(stmt);
            JdbcUtil.executeLargeUpdate(stmt);
            JdbcUtil.execute(stmt);
        }

        verify(stmt, never()).clearParameters();
        verify(stmt, never()).close();
        verify(driverResult).close();
    }

    private static Object execute(final PreparedStatement stmt, final int operation) throws SQLException {
        return switch (operation) {
            case 0 -> JdbcUtil.executeQuery(stmt);
            case 1 -> JdbcUtil.executeUpdate(stmt);
            case 2 -> JdbcUtil.executeLargeUpdate(stmt);
            case 3 -> JdbcUtil.execute(stmt);
            default -> throw new AssertionError("Unknown operation: " + operation);
        };
    }
}
