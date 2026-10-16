package com.landawn.abacus.jdbc;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.Mockito;

class AbstractQueryLifecycleTest extends AbstractQueryTestSupport {

    @Test
    public void testCloseAfterExecution_ClosedQuery() throws SQLException {
        query.close();

        // Changing the automatic-close policy only stores a flag and does not access the statement.
        assertSame(query, query.closeAfterExecution(false));
    }

    @Test
    public void testOnCloseChecksClosedStateBeforeNullHandler() throws SQLException {
        query.close();

        assertThrows(IllegalStateException.class, () -> query.onClose(null));

        verify(preparedStatement, times(1)).close();
    }

    @Tag("2025")
    @DisplayName("onClose handler registered after close should throw IllegalStateException")
    @Test
    public void testOnClose_AfterClose_ThrowsIllegalStateException() {
        query.close();

        assertThrows(IllegalStateException.class, () -> query.onClose(() -> {
        }));
    }

    @Test
    public void testOnCloseRegistrationCannotRacePastClose() throws Exception {
        final PreparedStatement stmt = Mockito.mock(PreparedStatement.class);
        final CoordinatedCloseQuery coordinatedQuery = new CoordinatedCloseQuery(stmt);
        final AtomicInteger closeCalls = new AtomicInteger();
        final Thread registration = new Thread(() -> coordinatedQuery.onClose(closeCalls::incrementAndGet), "query-close-handler-registration");

        registration.start();
        assertTrue(coordinatedQuery.stateChecked.await(1, TimeUnit.SECONDS));

        final Thread closer = new Thread(coordinatedQuery::close, "query-close");
        closer.start();

        // The synchronized registration section holds the same monitor used by close() until the callback has
        // been linked. The pre-fix implementation let close() finish here and then installed a
        // callback into an already-closed query, so the callback was permanently lost.
        final long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(1);
        while (closer.isAlive() && closer.getState() != Thread.State.BLOCKED && System.nanoTime() < deadline) {
            Thread.yield();
        }

        coordinatedQuery.continueRegistration.countDown();
        registration.join(TimeUnit.SECONDS.toMillis(1));
        closer.join(TimeUnit.SECONDS.toMillis(1));

        assertFalse(registration.isAlive());
        assertFalse(closer.isAlive());
        assertEquals(1, closeCalls.get());
        verify(stmt).close();
    }

    // close() logs warning when stmt.setFetchDirection throws during reset (L9919-9920)
    @Test
    public void testClose_WhenResetFetchDirectionFails_LogsWarning() throws SQLException {
        when(preparedStatement.getFetchDirection()).thenReturn(ResultSet.FETCH_REVERSE);
        // Only throw on reset call (FETCH_REVERSE), not on the initial set call (FETCH_FORWARD)
        doThrow(new SQLException("reset fetch dir failed")).when(preparedStatement).setFetchDirection(ResultSet.FETCH_REVERSE);

        query.setFetchDirection(FetchDirection.FORWARD); // stores defaultFetchDirection = FETCH_REVERSE

        // close() resets to FETCH_REVERSE which throws, but is swallowed
        assertDoesNotThrow((org.junit.jupiter.api.function.Executable) query::close);
    }

    // close() logs warning when stmt.setFetchSize throws during reset (L9927-9928)
    @Test
    public void testClose_WhenResetFetchSizeFails_LogsWarning() throws SQLException {
        when(preparedStatement.getFetchSize()).thenReturn(100);
        doThrow(new SQLException("reset fetch size failed")).when(preparedStatement).setFetchSize(100);

        query.setFetchSize(500); // stores defaultFetchSize = 100

        // close() resets to 100 which throws, but is swallowed
        assertDoesNotThrow((org.junit.jupiter.api.function.Executable) query::close);
    }

    // close() logs warning when stmt.setQueryTimeout throws during reset (L9943-9944)
    @Test
    public void testClose_WhenResetQueryTimeoutFails_LogsWarning() throws SQLException {
        when(preparedStatement.getQueryTimeout()).thenReturn(30);
        doThrow(new SQLException("reset timeout failed")).when(preparedStatement).setQueryTimeout(30);

        query.setQueryTimeout(60); // stores defaultQueryTimeout = 30

        // close() resets to 30 which throws, but is swallowed
        assertDoesNotThrow((org.junit.jupiter.api.function.Executable) query::close);
    }

    // close() logs warning when stmt.setMaxFieldSize throws during reset (L9935-9936)
    @Test
    public void testClose_WhenResetMaxFieldSizeFails_LogsWarning() throws SQLException {
        when(preparedStatement.getMaxFieldSize()).thenReturn(100);
        doThrow(new SQLException("reset maxFieldSize failed")).when(preparedStatement).setMaxFieldSize(100);

        query.setMaxFieldSize(512); // stores defaultMaxFieldSize = 100

        // close() resets to 100 which throws, but is swallowed
        assertDoesNotThrow((org.junit.jupiter.api.function.Executable) query::close);
    }

    // Regression: setMaxRows used to skip both capture and restore (twin-divergence with the four
    // sibling setters). When the underlying PreparedStatement comes from a statement pool (the
    // closeAfterExecution(false) path, or driver-side statement caching), a stale max-rows limit
    // from a prior caller would silently truncate later result sets.
    @Test
    public void testClose_RestoresMaxRows() throws SQLException {
        when(preparedStatement.getMaxRows()).thenReturn(50);

        query.setMaxRows(200); // capture default = 50, then apply 200

        verify(preparedStatement).setMaxRows(200);

        query.close();

        verify(preparedStatement).setMaxRows(50); // restored on close
    }

    @Test
    public void testClose_WhenResetMaxRowsFails_LogsWarning() throws SQLException {
        when(preparedStatement.getMaxRows()).thenReturn(50);
        doThrow(new SQLException("reset maxRows failed")).when(preparedStatement).setMaxRows(50);

        query.setMaxRows(200);

        assertDoesNotThrow((org.junit.jupiter.api.function.Executable) query::close);
    }

    // Regression: setLargeMaxRows used to skip both capture and restore.
    @Test
    public void testClose_RestoresLargeMaxRows() throws SQLException {
        when(preparedStatement.getLargeMaxRows()).thenReturn(75L);

        query.setLargeMaxRows(500L);

        verify(preparedStatement).setLargeMaxRows(500L);

        query.close();

        verify(preparedStatement).setLargeMaxRows(75L);
    }

    @Test
    public void testClose_RestoresOriginalLimitAfterMixingMaxRowsSetters() throws SQLException {
        final AtomicReference<Long> rowLimit = new AtomicReference<>(50L);
        when(preparedStatement.getMaxRows()).thenAnswer(invocation -> rowLimit.get().intValue());
        when(preparedStatement.getLargeMaxRows()).thenAnswer(invocation -> rowLimit.get());
        Mockito.doAnswer(invocation -> {
            rowLimit.set(((Integer) invocation.getArgument(0)).longValue());
            return null;
        }).when(preparedStatement).setMaxRows(anyInt());
        Mockito.doAnswer(invocation -> {
            rowLimit.set(invocation.getArgument(0));
            return null;
        }).when(preparedStatement).setLargeMaxRows(anyLong());

        query.setMaxRows(200).setLargeMaxRows(300L).setMaxRows(400);
        assertEquals(400L, rowLimit.get().longValue());

        query.close();

        assertEquals(50L, rowLimit.get().longValue());
        verify(preparedStatement, never()).getLargeMaxRows();
    }

    @Test
    public void testClose_RestoresOriginalLargeLimitAfterUsingMaxRows() throws SQLException {
        final long originalLimit = (long) Integer.MAX_VALUE + 100;
        when(preparedStatement.getLargeMaxRows()).thenReturn(originalLimit);
        when(preparedStatement.getMaxRows()).thenThrow(new SQLException("Row limit exceeds the int range"));

        query.setLargeMaxRows(500L).setMaxRows(200).setLargeMaxRows(300L);
        query.close();

        verify(preparedStatement).setLargeMaxRows(originalLimit);
        verify(preparedStatement, never()).getMaxRows();
        verify(preparedStatement).close();
    }

    @Test
    public void testClose_WhenResetLargeMaxRowsFails_LogsWarning() throws SQLException {
        when(preparedStatement.getLargeMaxRows()).thenReturn(75L);
        doThrow(new SQLException("reset largeMaxRows failed")).when(preparedStatement).setLargeMaxRows(75L);

        query.setLargeMaxRows(500L);

        assertDoesNotThrow((org.junit.jupiter.api.function.Executable) query::close);
    }

    @Test
    public void testCloseReleasesCapturedHandlerChain() {
        query.onClose(() -> {
        });

        query.close();

        assertNull(query.closeHandler);
    }

    @Test
    public void testClosePreservesFailuresFromEveryCloseHandler() {
        final RuntimeException olderFailure = new RuntimeException("older handler failed");
        final RuntimeException newerFailure = new RuntimeException("newer handler failed");

        query.onClose(() -> {
            throw olderFailure;
        }).onClose(() -> {
            throw newerFailure;
        });

        final RuntimeException thrown = assertThrows(RuntimeException.class, query::close);

        assertSame(newerFailure, thrown);
        assertArrayEquals(new Throwable[] { olderFailure }, thrown.getSuppressed());
    }

    // Statement cleanup logs SQL and runtime exceptions from the driver (see AbstractQueryReviewFixTest), so an Error is
    // used to exercise a cleanup failure that still propagates.
    @Test
    public void testClosePreservesStatementCleanupFailureWhenCloseHandlerAlsoFails() throws SQLException {
        final Error statementFailure = new Error("statement reset failed");
        final AssertionError handlerFailure = new AssertionError("close handler failed");
        when(preparedStatement.getFetchSize()).thenReturn(10);
        query.setFetchSize(20);
        doThrow(statementFailure).when(preparedStatement).setFetchSize(10);
        query.onClose(() -> {
            throw handlerFailure;
        });

        final Error thrown = assertThrows(Error.class, query::close);

        assertSame(statementFailure, thrown);
        assertArrayEquals(new Throwable[] { handlerFailure }, thrown.getSuppressed());
        verify(preparedStatement).close();
        assertDoesNotThrow(query::close);
    }

    @Test
    public void testCloseDoesNotSuppressStatementCleanupFailureOntoItself() throws SQLException {
        final AssertionError failure = new AssertionError("cleanup failed");
        when(preparedStatement.getFetchSize()).thenReturn(10);
        query.setFetchSize(20);
        doThrow(failure).when(preparedStatement).setFetchSize(10);
        query.onClose(() -> {
            throw failure;
        });

        final AssertionError thrown = assertThrows(AssertionError.class, query::close);

        assertSame(failure, thrown);
        assertEquals(0, thrown.getSuppressed().length);
        verify(preparedStatement).close();
    }

    @ParameterizedTest(name = "sameFailure={0}")
    @ValueSource(booleans = { false, true })
    void close_newerHandlerFails_stillRunsOlderHandlerWithoutSelfSuppression(final boolean sameFailure) throws SQLException {
        final IllegalStateException failure = new IllegalStateException("close failed");
        final AtomicInteger olderCalls = new AtomicInteger();
        query.onClose(() -> {
            olderCalls.incrementAndGet();
            if (sameFailure) {
                throw failure;
            }
        }).onClose(() -> {
            throw failure;
        });

        assertSame(failure, assertThrows(IllegalStateException.class, query::close));
        assertEquals(1, olderCalls.get());
        assertEquals(0, failure.getSuppressed().length);
        verify(preparedStatement).close();
    }

    // An Error is used because statement cleanup logs SQL and runtime exceptions from the driver.
    @Test
    void close_statementCleanupFails_stillRunsSuccessfulHandler() throws SQLException {
        final Error failure = new Error("statement reset failed");
        final AtomicInteger handlerCalls = new AtomicInteger();
        when(preparedStatement.getFetchSize()).thenReturn(10);
        query.setFetchSize(20).onClose(handlerCalls::incrementAndGet);
        doThrow(failure).when(preparedStatement).setFetchSize(10);

        assertSame(failure, assertThrows(Error.class, query::close));
        assertEquals(1, handlerCalls.get());
        assertEquals(0, failure.getSuppressed().length);
        verify(preparedStatement).close();
    }

    @ParameterizedTest(name = "returnsValue={0}")
    @ValueSource(booleans = { false, true })
    void async_callbackFails_preservesFailureAndSuppressesCleanup(final boolean returnsValue) throws Exception {
        final SQLException failure = new SQLException("callback failed");
        final IllegalStateException cleanup = new IllegalStateException("close failed");
        query.onClose(() -> {
            throw cleanup;
        });

        final var future = returnsValue ? query.callAsync(q -> {
            throw failure;
        }, Runnable::run) : query.runAsync(q -> {
            throw failure;
        }, Runnable::run);
        final ExecutionException thrown = assertThrows(ExecutionException.class, () -> future.get(1, TimeUnit.SECONDS));

        assertSame(failure, thrown.getCause());
        assertArrayEquals(new Throwable[] { cleanup }, failure.getSuppressed());
        assertTrue(query.isClosed);
        verify(preparedStatement).close();
    }

    @Test
    void cancel_beforeExecution_survivesCleanupFailureAndDoesNotRunCallback() throws Exception {
        final AtomicReference<Runnable> scheduled = new AtomicReference<>();
        final AtomicInteger callbackCalls = new AtomicInteger();
        query.onClose(() -> {
            throw new IllegalStateException("close failed");
        });
        final var future = query.runAsync(q -> callbackCalls.incrementAndGet(), scheduled::set);

        assertTrue(future.cancel(false));
        scheduled.get().run();

        assertTrue(future.isCancelled());
        assertTrue(query.isClosed);
        assertEquals(0, callbackCalls.get());
        verify(preparedStatement).close();
    }

    static final class CoordinatedCloseQuery extends AbstractQuery<PreparedStatement, CoordinatedCloseQuery> {
        final CountDownLatch stateChecked = new CountDownLatch(1);
        final CountDownLatch continueRegistration = new CountDownLatch(1);

        CoordinatedCloseQuery(final PreparedStatement stmt) {
            super(stmt);
        }

        @Override
        void assertNotClosed() {
            super.assertNotClosed();
            if (!Thread.holdsLock(this)) {
                return;
            }
            stateChecked.countDown();

            try {
                continueRegistration.await();
            } catch (final InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new AssertionError(e);
            }
        }
    }
}
