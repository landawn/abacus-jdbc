package com.landawn.abacus.jdbc;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.io.IOException;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.Mockito;

import com.landawn.abacus.util.Throwables;

/**
 * Close and stream-cleanup paths of {@link AbstractQuery} facing a checked exception that a driver, an overriding
 * {@code closeStatement()}, or a row mapper/filter/extractor throws without declaring it (Kotlin, a sneaky throw).
 */
class AbstractQueryUndeclaredCheckedCloseTest extends AbstractQueryTestSupport {

    @SuppressWarnings("unchecked")
    private static <X extends Throwable> void throwUndeclared(final Throwable failure) throws X {
        throw (X) failure;
    }

    private static <T> T throwUndeclaredValue(final Throwable failure) {
        throwUndeclared(failure);
        return null;
    }

    // ------------------------------------------------------------------ closeStatement() resets / close()

    // The statement property resets caught SQLException | RuntimeException only: an undeclared checked one skipped the
    // remaining resets and escaped closeStatement(), and close() (catching RuntimeException | Error) then skipped the close
    // handler that releases a data-source query's connection, and threw it out of close().
    @ParameterizedTest(name = "failing reset: {0}")
    @ValueSource(strings = { "fetchDirection", "fetchSize", "maxFieldSize", "queryTimeout", "maxRows", "largeMaxRows" })
    void close_resetThrowsUndeclaredCheckedException_runsRemainingResetsClosesStatementAndRunsHandler(final String failingReset) throws SQLException {
        final IOException resetFailure = new IOException("reset failed, undeclared");
        final boolean large = "largeMaxRows".equals(failingReset);
        final AtomicInteger connectionReleases = new AtomicInteger();
        when(preparedStatement.getFetchDirection()).thenReturn(ResultSet.FETCH_REVERSE);
        when(preparedStatement.getFetchSize()).thenReturn(100);
        when(preparedStatement.getMaxFieldSize()).thenReturn(200);
        when(preparedStatement.getQueryTimeout()).thenReturn(30);
        when(preparedStatement.getMaxRows()).thenReturn(50);
        when(preparedStatement.getLargeMaxRows()).thenReturn(75L);

        query.setFetchDirection(FetchDirection.FORWARD).setFetchSize(500).setMaxFieldSize(400).setQueryTimeout(60);

        if (large) {
            query.setLargeMaxRows(500L);
        } else {
            query.setMaxRows(500);
        }

        query.onClose(connectionReleases::incrementAndGet);

        final org.mockito.stubbing.Answer<Object> fail = invocation -> {
            throw resetFailure;
        };

        switch (failingReset) {
            case "fetchDirection" -> doAnswer(fail).when(preparedStatement).setFetchDirection(ResultSet.FETCH_REVERSE);
            case "fetchSize" -> doAnswer(fail).when(preparedStatement).setFetchSize(100);
            case "maxFieldSize" -> doAnswer(fail).when(preparedStatement).setMaxFieldSize(200);
            case "queryTimeout" -> doAnswer(fail).when(preparedStatement).setQueryTimeout(30);
            case "maxRows" -> doAnswer(fail).when(preparedStatement).setMaxRows(50);
            default -> doAnswer(fail).when(preparedStatement).setLargeMaxRows(75L);
        }

        assertDoesNotThrow(query::close);

        verify(preparedStatement).setFetchDirection(ResultSet.FETCH_REVERSE);
        verify(preparedStatement).setFetchSize(100);
        verify(preparedStatement).setMaxFieldSize(200);
        verify(preparedStatement).setQueryTimeout(30);

        if (large) {
            verify(preparedStatement).setLargeMaxRows(75L);
        } else {
            verify(preparedStatement).setMaxRows(50);
        }

        verify(preparedStatement).close();
        assertEquals(1, connectionReleases.get());
        assertTrue(query.isClosed);
        assertEquals(0, resetFailure.getSuppressed().length);
    }

    // A swallowed InterruptedException must leave the thread's interrupt status set.
    @Test
    void close_resetThrowsUndeclaredInterruptedException_restoresInterruptStatus() throws SQLException {
        final AtomicInteger connectionReleases = new AtomicInteger();
        when(preparedStatement.getFetchSize()).thenReturn(100);
        query.setFetchSize(500).onClose(connectionReleases::incrementAndGet);
        doAnswer(invocation -> {
            throw new InterruptedException("interrupted while resetting");
        }).when(preparedStatement).setFetchSize(100);

        try {
            assertDoesNotThrow(query::close);
            assertTrue(Thread.currentThread().isInterrupted());
        } finally {
            Thread.interrupted();
        }

        verify(preparedStatement).close();
        assertEquals(1, connectionReleases.get());
    }

    // close() caught only RuntimeException | Error from closeStatement(): an undeclared checked exception from an overriding
    // closeStatement() skipped the close handler (the connection release) and was thrown without the handler's failure.
    @ParameterizedTest(name = "handlerFails={0}")
    @ValueSource(booleans = { false, true })
    void close_closeStatementThrowsUndeclaredCheckedException_runsHandlerAndKeepsItPrimary(final boolean handlerFails) {
        final IOException cleanupFailure = new IOException("statement cleanup failed, undeclared");
        final IllegalStateException handlerFailure = new IllegalStateException("close handler failed");
        final AtomicInteger connectionReleases = new AtomicInteger();
        final FailingCleanupQuery failingQuery = new FailingCleanupQuery(preparedStatement, cleanupFailure);
        failingQuery.onClose(() -> {
            connectionReleases.incrementAndGet();

            if (handlerFails) {
                throw handlerFailure;
            }
        });

        assertSame(cleanupFailure, assertThrows(Throwable.class, failingQuery::close));

        assertEquals(1, connectionReleases.get());
        assertArrayEquals(handlerFails ? new Throwable[] { handlerFailure } : new Throwable[0], cleanupFailure.getSuppressed());
        assertDoesNotThrow(failingQuery::close);
    }

    static final class FailingCleanupQuery extends AbstractQuery<PreparedStatement, FailingCleanupQuery> {
        private final Throwable cleanupFailure;

        FailingCleanupQuery(final PreparedStatement stmt, final Throwable cleanupFailure) {
            super(stmt);
            this.cleanupFailure = cleanupFailure;
        }

        @Override
        protected void closeStatement() {
            super.closeStatement();
            throwUndeclared(cleanupFailure);
        }
    }

    // ------------------------------------------------------------------ stream traversal and deferred execution

    // preserveTraversalFailure and the deferred-execution suppliers caught RuntimeException | Error only: an undeclared
    // checked failure skipped their cleanup. A stream consumed through its iterator then left the query (and a data-source
    // query's connection) open; a terminal operation's own close keeps the failure primary, so both ways are covered.
    @ParameterizedTest(name = "{0}, {2}, autoClose={3}")
    @MethodSource("streamTraversalFailures")
    void stream_undeclaredCheckedTraversalFailure_closesAndStaysPrimaryOverCloseHandlerFailure(final String operationName,
            final Throwables.BiFunction<TestQuery, IOException, com.landawn.abacus.util.stream.Stream<?>, SQLException> streamFactory,
            final Consumption consumption, final boolean autoClose) throws SQLException {
        final IOException traversalFailure = new IOException("row mapping failed, undeclared");
        final IllegalStateException cleanup = new IllegalStateException("close handler failed");
        final ResultSet rs = mockResultSet();
        when(rs.next()).thenReturn(true);
        when(preparedStatement.executeQuery()).thenReturn(rs);
        query.closeAfterExecution(autoClose).onClose(() -> {
            throw cleanup;
        });

        final Throwable thrown = assertThrows(Throwable.class, () -> consumption.consume(streamFactory.apply(query, traversalFailure)));

        assertSame(traversalFailure, thrown);
        assertArrayEquals(autoClose ? new Throwable[] { cleanup } : new Throwable[0], thrown.getSuppressed());
        assertEquals(autoClose, query.isClosed);
        verify(rs).close();

        if (!autoClose) {
            assertSame(cleanup, assertThrows(IllegalStateException.class, query::close));
        }
    }

    private static Stream<Arguments> streamTraversalFailures() {
        // streamAllResultSets is not listed: its extraction step (Stream.mapE) wraps a checked exception in a runtime one.
        return Stream
                .<Arguments> of(
                        Arguments.of("stream(rowMapper)",
                                (Throwables.BiFunction<TestQuery, IOException, com.landawn.abacus.util.stream.Stream<?>, SQLException>) (q, e) -> q
                                        .stream(rs -> throwUndeclaredValue(e))),
                        Arguments.of("stream(biRowMapper)",
                                (Throwables.BiFunction<TestQuery, IOException, com.landawn.abacus.util.stream.Stream<?>, SQLException>) (q, e) -> q
                                        .stream((rs, labels) -> throwUndeclaredValue(e))),
                        Arguments.of("stream(rowFilter,rowMapper)",
                                (Throwables.BiFunction<TestQuery, IOException, com.landawn.abacus.util.stream.Stream<?>, SQLException>) (q, e) -> q
                                        .stream(rs -> throwUndeclaredValue(e), rs -> "x")))
                .flatMap(AbstractQueryUndeclaredCheckedCloseTest::withConsumptionAndAutoClose);
    }

    @ParameterizedTest(name = "{0}, {2}, autoClose={3}")
    @MethodSource("streamExecutionFailures")
    void stream_executionThrowsUndeclaredCheckedException_closesAndStaysPrimaryOverCloseHandlerFailure(final String operationName,
            final Throwables.Function<TestQuery, com.landawn.abacus.util.stream.Stream<?>, SQLException> streamFactory, final Consumption consumption,
            final boolean autoClose) throws SQLException {
        final IOException executionFailure = new IOException("execution failed, undeclared");
        final IllegalStateException cleanup = new IllegalStateException("close handler failed");
        doAnswer(invocation -> {
            throw executionFailure;
        }).when(preparedStatement).executeQuery();
        doAnswer(invocation -> {
            throw executionFailure;
        }).when(preparedStatement).execute();
        when(preparedStatement.getUpdateCount()).thenReturn(-1);
        query.closeAfterExecution(autoClose).onClose(() -> {
            throw cleanup;
        });

        final Throwable thrown = assertThrows(Throwable.class, () -> consumption.consume(streamFactory.apply(query)));

        assertSame(executionFailure, thrown);
        assertArrayEquals(autoClose ? new Throwable[] { cleanup } : new Throwable[0], thrown.getSuppressed());
        assertEquals(autoClose, query.isClosed);

        if (autoClose) {
            verify(preparedStatement).close();
        } else {
            verify(preparedStatement, never()).close();
            assertSame(cleanup, assertThrows(IllegalStateException.class, query::close));
        }
    }

    private static Stream<Arguments> streamExecutionFailures() {
        return Stream
                .<Arguments> of(
                        Arguments.of("stream()", (Throwables.Function<TestQuery, com.landawn.abacus.util.stream.Stream<?>, SQLException>) TestQuery::stream),
                        Arguments.of("stream(rowMapper)",
                                (Throwables.Function<TestQuery, com.landawn.abacus.util.stream.Stream<?>, SQLException>) q -> q.stream(rs -> rs.getString(1))),
                        Arguments.of("streamAllResultSets",
                                (Throwables.Function<TestQuery, com.landawn.abacus.util.stream.Stream<?>, SQLException>) q -> q
                                        .streamAllResultSets((Jdbc.ResultExtractor<Integer>) rs -> 1)))
                .flatMap(AbstractQueryUndeclaredCheckedCloseTest::withConsumptionAndAutoClose);
    }

    private static Stream<Arguments> withConsumptionAndAutoClose(final Arguments arguments) {
        return Stream.of(Consumption.values())
                .flatMap(consumption -> Stream.of(false, true).map(autoClose -> Arguments.of(arguments.get()[0], arguments.get()[1], consumption, autoClose)));
    }

    /** How a test consumes the query's stream; neither closes it explicitly. */
    enum Consumption {
        /** A terminal operation, which closes the stream itself. */
        TO_LIST,
        /** The stream's iterator, which leaves the stream open. */
        ITERATOR;

        void consume(final com.landawn.abacus.util.stream.Stream<?> stream) {
            if (this == TO_LIST) {
                stream.toList();
            } else {
                stream.iterator().next();
            }
        }
    }

    // ------------------------------------------------------------------ closeAllResultsAndQueryIfAllowed

    // Closing the result iterator of a Connector/J streaming statement reads its remaining results. A checked exception the
    // driver throws there undeclared must be suppressed onto an extraction failure, not replace it.
    @Test
    void query2ResultSets_streamingDrainThrowsUndeclaredCheckedException_isSuppressedOnTheExtractionFailure() throws SQLException {
        final SQLException extractionFailure = new SQLException("extraction failed");
        final IOException drainFailure = new IOException("reading the remaining streamed results failed, undeclared");
        final IllegalStateException cleanup = new IllegalStateException("close handler failed");
        final AtomicInteger connectionReleases = new AtomicInteger();
        final ResultSet rs1 = mockResultSet();
        mockConnectorJStreaming();
        when(preparedStatement.execute()).thenReturn(true);
        when(preparedStatement.getResultSet()).thenReturn(rs1).thenAnswer(invocation -> {
            throw drainFailure;
        });
        query.onClose(connectionReleases::incrementAndGet).onClose(() -> {
            throw cleanup;
        });

        final SQLException thrown = assertThrows(SQLException.class, () -> query.query2ResultSets((rs, labels) -> {
            throw extractionFailure;
        }, (rs, labels) -> "second"));

        assertSame(extractionFailure, thrown);
        assertArrayEquals(new Throwable[] { drainFailure, cleanup }, thrown.getSuppressed());
        assertTrue(query.isClosed);
        assertEquals(1, connectionReleases.get());
        verify(preparedStatement).close();
    }

    // Without an earlier failure the drain failure is the one thrown (the results were not fully read), the query is still
    // closed, and a failing close handler is suppressed onto it instead of replacing it.
    @Test
    void query2ResultSets_streamingDrainThrowsUndeclaredCheckedException_isThrownAndClosesQuery() throws SQLException {
        final IOException drainFailure = new IOException("reading the remaining streamed results failed, undeclared");
        final IllegalStateException cleanup = new IllegalStateException("close handler failed");
        final AtomicInteger connectionReleases = new AtomicInteger();
        final ResultSet rs1 = mockResultSet();
        final ResultSet rs2 = mockResultSet();
        mockConnectorJStreaming();
        // Two of three streamed results are read; closing the iterator reads the third.
        when(preparedStatement.execute()).thenReturn(true);
        when(preparedStatement.getMoreResults()).thenReturn(true);
        when(preparedStatement.getResultSet()).thenReturn(rs1, rs2).thenAnswer(invocation -> {
            throw drainFailure;
        });
        query.onClose(connectionReleases::incrementAndGet).onClose(() -> {
            throw cleanup;
        });

        final Throwable thrown = assertThrows(Throwable.class, () -> query.query2ResultSets((rs, labels) -> "first", (rs, labels) -> "second"));

        assertSame(drainFailure, thrown);
        assertArrayEquals(new Throwable[] { cleanup }, thrown.getSuppressed());
        assertTrue(query.isClosed);
        assertEquals(1, connectionReleases.get());
        verify(preparedStatement).close();
    }

    // The stream's close reaches the same cleanup when it is closed before every result was read. The stream's close wraps
    // the checked exception thrown by its close action in a runtime one.
    @Test
    void streamAllResultSets_closedEarly_streamingDrainThrowsUndeclaredCheckedException_isThrownAndClosesQuery() throws SQLException {
        final IOException drainFailure = new IOException("reading the remaining streamed results failed, undeclared");
        final IllegalStateException cleanup = new IllegalStateException("close handler failed");
        final AtomicInteger connectionReleases = new AtomicInteger();
        final ResultSet rs1 = mockResultSet();
        mockConnectorJStreaming();
        when(preparedStatement.execute()).thenReturn(true);
        when(preparedStatement.getResultSet()).thenReturn(rs1).thenAnswer(invocation -> {
            throw drainFailure;
        });
        query.onClose(connectionReleases::incrementAndGet).onClose(() -> {
            throw cleanup;
        });

        final Throwable thrown = assertThrows(Throwable.class, () -> {
            try (com.landawn.abacus.util.stream.Stream<String> results = query.streamAllResultSets((Jdbc.ResultExtractor<String>) rs -> "first")) {
                assertEquals("first", results.iterator().next());
            }
        });

        assertSame(drainFailure, thrown.getCause());
        assertArrayEquals(new Throwable[] { cleanup }, drainFailure.getSuppressed());
        assertTrue(query.isClosed);
        assertEquals(1, connectionReleases.get());
        verify(preparedStatement).close();
    }

    private void mockConnectorJStreaming() throws SQLException {
        final Connection connection = Mockito.mock(Connection.class);
        final DatabaseMetaData databaseMetaData = Mockito.mock(DatabaseMetaData.class);
        when(preparedStatement.getConnection()).thenReturn(connection);
        when(connection.getMetaData()).thenReturn(databaseMetaData);
        when(databaseMetaData.getDriverName()).thenReturn("MySQL Connector/J");
        when(preparedStatement.getFetchSize()).thenReturn(Integer.MIN_VALUE);
        when(preparedStatement.getResultSetType()).thenReturn(ResultSet.TYPE_FORWARD_ONLY);
        when(preparedStatement.getResultSetConcurrency()).thenReturn(ResultSet.CONCUR_READ_ONLY);
        when(preparedStatement.getUpdateCount()).thenReturn(-1);
    }

    private static ResultSet mockResultSet() throws SQLException {
        final ResultSet rs = Mockito.mock(ResultSet.class);
        final ResultSetMetaData metadata = Mockito.mock(ResultSetMetaData.class);
        when(rs.getMetaData()).thenReturn(metadata);
        when(metadata.getColumnCount()).thenReturn(1);
        when(metadata.getColumnLabel(1)).thenReturn("c");
        return rs;
    }
}
