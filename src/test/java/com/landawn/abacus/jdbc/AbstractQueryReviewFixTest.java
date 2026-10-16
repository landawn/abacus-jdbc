package com.landawn.abacus.jdbc;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;

/**
 * Regression tests for the review fix in {@link AbstractQuery#closeStatement()}: a {@link RuntimeException} thrown
 * by the driver while restoring a statement property during close must be logged and swallowed like a
 * {@link SQLException}. Before the fix it escaped {@link AbstractQuery#close()} (e.g., out of a try-with-resources
 * block) and skipped every remaining reset, leaving those properties changed on a pooled statement.
 */
class AbstractQueryReviewFixTest extends AbstractQueryTestSupport {

    @Test
    public void testClose_RuntimeExceptionFromFirstResetDoesNotSkipRemainingResets() throws SQLException {
        when(preparedStatement.getFetchDirection()).thenReturn(ResultSet.FETCH_REVERSE);
        when(preparedStatement.getFetchSize()).thenReturn(10);
        when(preparedStatement.getMaxFieldSize()).thenReturn(100);
        when(preparedStatement.getQueryTimeout()).thenReturn(30);
        when(preparedStatement.getMaxRows()).thenReturn(50);
        doThrow(new IllegalStateException("driver failed to reset fetch direction")).when(preparedStatement).setFetchDirection(ResultSet.FETCH_REVERSE);

        query.setFetchDirection(FetchDirection.FORWARD).setFetchSize(500).setMaxFieldSize(512).setQueryTimeout(60).setMaxRows(200);

        assertDoesNotThrow(() -> query.close());

        verify(preparedStatement).setFetchDirection(ResultSet.FETCH_REVERSE);
        verify(preparedStatement).setFetchSize(10);
        verify(preparedStatement).setMaxFieldSize(100);
        verify(preparedStatement).setQueryTimeout(30);
        verify(preparedStatement).setMaxRows(50);
        verify(preparedStatement).close();
    }

    @Test
    public void testClose_RuntimeExceptionFromEveryResetIsSwallowedAndCloseHandlerRuns() throws SQLException {
        when(preparedStatement.getFetchSize()).thenReturn(10);
        when(preparedStatement.getLargeMaxRows()).thenReturn(75L);
        doThrow(new IllegalStateException("driver failed to reset fetch size")).when(preparedStatement).setFetchSize(10);
        doThrow(new UnsupportedOperationException("driver failed to reset large max rows")).when(preparedStatement).setLargeMaxRows(75L);
        final AtomicInteger closeHandlerCalls = new AtomicInteger();

        query.setFetchSize(500).setLargeMaxRows(1000L).onClose(closeHandlerCalls::incrementAndGet);

        assertDoesNotThrow(() -> query.close());

        verify(preparedStatement).setLargeMaxRows(75L);
        verify(preparedStatement).close();
        assertEquals(1, closeHandlerCalls.get());
    }

    // Errors are not cleanup noise: they still propagate, after the statement has been closed.
    @Test
    public void testClose_ErrorFromResetStillPropagates() throws SQLException {
        when(preparedStatement.getQueryTimeout()).thenReturn(30);
        final AssertionError failure = new AssertionError("fatal reset failure");
        doThrow(failure).when(preparedStatement).setQueryTimeout(30);

        query.setQueryTimeout(60);

        assertSame(failure, assertThrows(AssertionError.class, () -> query.close()));
        verify(preparedStatement).close();
    }
}
