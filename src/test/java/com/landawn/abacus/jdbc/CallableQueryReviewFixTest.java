package com.landawn.abacus.jdbc;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.sql.SQLException;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;

/**
 * Regression tests for the review fix in {@link CallableQuery#closeStatement()}: a {@link RuntimeException}
 * thrown by {@code CallableStatement.clearParameters()} during close must be logged and swallowed (matching
 * the best-effort cleanup convention of {@code JdbcUtil.clearParameters}), not propagated out of
 * {@link CallableQuery#close()} where it would escape try-with-resources blocks.
 */
class CallableQueryReviewFixTest extends CallableQueryTestSupport {

    @Test
    public void testCloseStatement_ClearParametersRuntimeException_swallowed() throws SQLException {
        doThrow(new RuntimeException("driver failed to clear parameters")).when(callableStatement).clearParameters();

        // Pre-fix, the RuntimeException propagated out of close(); post-fix it is logged and swallowed.
        assertDoesNotThrow(() -> callableQuery.close());

        // The finally block must still run: the statement itself is closed.
        verify(callableStatement).clearParameters();
        verify(callableStatement).close();
    }

    // The SQLException variant is covered by CallableQueryLifecycleTest.testCloseStatement_ClearParametersThrows.

    // The property resets done by super.closeStatement() follow the same best-effort policy: runtime failures from
    // both cleanup steps are logged, the remaining reset still runs, the statement is closed and the close handler runs.
    @Test
    public void testCloseStatement_RuntimeExceptionsFromClearAndResetAreSwallowed() throws SQLException {
        when(callableStatement.getFetchSize()).thenReturn(10);
        when(callableStatement.getQueryTimeout()).thenReturn(30);
        doThrow(new IllegalStateException("driver failed to clear parameters")).when(callableStatement).clearParameters();
        doThrow(new IllegalStateException("driver failed to reset fetch size")).when(callableStatement).setFetchSize(10);
        final AtomicInteger closeHandlerCalls = new AtomicInteger();

        callableQuery.setFetchSize(500).setQueryTimeout(60).onClose(closeHandlerCalls::incrementAndGet);

        assertDoesNotThrow(() -> callableQuery.close());

        verify(callableStatement).setFetchSize(10);
        verify(callableStatement).setQueryTimeout(30);
        verify(callableStatement).close();
        assertEquals(1, closeHandlerCalls.get());
    }
}
