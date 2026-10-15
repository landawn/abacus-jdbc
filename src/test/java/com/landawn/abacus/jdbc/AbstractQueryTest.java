package com.landawn.abacus.jdbc;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
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
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import com.landawn.abacus.exception.UncheckedSQLException;
import com.landawn.abacus.util.NoCachingNoUpdating.DisposableObjArray;
import com.landawn.abacus.util.Throwables;
import com.landawn.abacus.util.stream.Stream;

class AbstractQueryTest extends AbstractQueryTestSupport {

    // Custom batch actions bypass the default PreparedStatement.addBatch call.
    @Test
    public void testConfigAddBatchAction() throws SQLException {
        query.configAddBatchAction((it, stmt) -> stmt.setInt(9, 99));

        TestQuery result = query.addBatch();

        assertSame(query, result);
        verify(preparedStatement).setInt(9, 99);
        verify(preparedStatement, never()).addBatch();
    }

    @Test
    public void testConfigAddBatchActionRejectsNullAndPreservesClosedStatePrecedence() throws SQLException {
        final IllegalArgumentException failure = assertThrows(IllegalArgumentException.class, () -> query.configAddBatchAction(null));

        assertTrue(failure.getMessage().contains("addBatchAction"));
        assertTrue(query.isClosed);
        verify(preparedStatement).close();
        verify(preparedStatement, never()).addBatch();

        Mockito.clearInvocations(preparedStatement);
        assertThrows(IllegalStateException.class, () -> query.configAddBatchAction(null));
        Mockito.verifyNoInteractions(preparedStatement);
    }

    // ifExistsOrElse(BiRowConsumer, Runnable) – orElseAction runs when no rows (L7972)
    @Test
    public void testIfExistsOrElse_BiRowConsumer_OrElseActionRuns() throws SQLException {
        final ResultSet rs = Mockito.mock(ResultSet.class);
        final ResultSetMetaData meta = Mockito.mock(ResultSetMetaData.class);
        when(preparedStatement.executeQuery()).thenReturn(rs);
        when(rs.getMetaData()).thenReturn(meta);
        when(meta.getColumnCount()).thenReturn(0);
        when(rs.next()).thenReturn(false); // no rows

        final boolean[] called = { false };
        query.ifExistsOrElse((r, labels) -> {
        }, () -> called[0] = true);

        assertTrue(called[0]);
    }

    // allMatch(BiRowFilter) returns false when a row fails the filter (L8302)
    @Test
    public void testAllMatch_BiRowFilter_ReturnsFalseWhenRowFails() throws SQLException {
        final ResultSet rs = Mockito.mock(ResultSet.class);
        final ResultSetMetaData meta = Mockito.mock(ResultSetMetaData.class);
        when(preparedStatement.executeQuery()).thenReturn(rs);
        when(rs.getMetaData()).thenReturn(meta);
        when(meta.getColumnCount()).thenReturn(0);
        when(rs.next()).thenReturn(true, false);

        final boolean result = query.allMatch((r, labels) -> false);

        assertFalse(result);
    }

    // insert(BiRowMapper, Predicate): rs.next() returns false → returns Optional.empty() (L8844)
    @Test
    public void testInsert_NoGeneratedKey_ReturnsEmpty() throws SQLException {
        ResultSet generatedKeys = Mockito.mock(ResultSet.class);
        when(preparedStatement.getGeneratedKeys()).thenReturn(generatedKeys);
        when(generatedKeys.next()).thenReturn(false);

        com.landawn.abacus.util.u.Optional<?> result = query.insert((Jdbc.BiRowMapper<Long>) (rs, cols) -> rs.getLong(1), id -> id == null || (Long) id == 0L);
        assertTrue(result.isEmpty());
    }

    @Tag("2025")
    @DisplayName("assertNotClosed should include class name in error message")
    @Test
    public void testAssertNotClosed_ErrorMessageContainsClassName() {
        query.close();

        final IllegalStateException ise = assertThrows(IllegalStateException.class, () -> query.update());
        assertTrue(ise.getMessage().contains("TestQuery"));
        assertTrue(ise.getMessage().contains("closed"));
    }

    @Test
    public void testCheckArgNotNull_NullArg_ThrowsIllegalArgument() {
        IllegalArgumentException iae = assertThrows(IllegalArgumentException.class, () -> query.checkArgNotNull(null, "myParam"));
        assertTrue(iae.getMessage().contains("myParam"));
    }

    @Test
    public void testCheckArgNotNull_CloseHandlerThrows_SuppressedException() {
        final RuntimeException closeEx = new RuntimeException("close handler failed");
        query.onClose(() -> {
            throw closeEx;
        });

        IllegalArgumentException iae = assertThrows(IllegalArgumentException.class, () -> query.checkArgNotNull(null, "someArg"));

        assertTrue(iae.getMessage().contains("someArg"));
        assertEquals(1, iae.getSuppressed().length);
        assertSame(closeEx, iae.getSuppressed()[0]);
    }

    @Test
    public void testCheckArgNotNull_CloseHandlerThrowsError_SuppressedException() {
        final AssertionError closeError = new AssertionError("close handler failed");
        query.onClose(() -> {
            throw closeError;
        });

        final IllegalArgumentException iae = assertThrows(IllegalArgumentException.class, () -> query.checkArgNotNull(null, "someArg"));

        assertTrue(iae.getMessage().contains("someArg"));
        assertEquals(1, iae.getSuppressed().length);
        assertSame(closeError, iae.getSuppressed()[0]);
    }

    @Test
    public void testCheckArg_FalseCondition_ThrowsIllegalArgument() {
        IllegalArgumentException iae = assertThrows(IllegalArgumentException.class, () -> query.checkArgument(false, "condition must be true"));
        assertEquals("condition must be true", iae.getMessage());
    }

    @Test
    public void testCheckArg_CloseHandlerThrows_SuppressedException() {
        final RuntimeException closeEx = new RuntimeException("close handler failed");
        query.onClose(() -> {
            throw closeEx;
        });

        IllegalArgumentException iae = assertThrows(IllegalArgumentException.class, () -> query.checkArgument(false, "bad condition"));

        assertEquals("bad condition", iae.getMessage());
        assertEquals(1, iae.getSuppressed().length);
        assertSame(closeEx, iae.getSuppressed()[0]);
    }

    // configureStatement(Consumer<Stmt>) — exception path closes (line 4305)
    @Test
    public void testConfigStmt_Consumer_ThrowsClosesQuery() {
        assertThrows(SQLException.class, () -> query.configureStatement((Throwables.Consumer<PreparedStatement, SQLException>) stmt -> {
            throw new SQLException("configureStatement failed");
        }));
    }

    // configureStatement(BiConsumer<This,Stmt>) — exception path closes (line 4345)
    @Test
    public void testConfigStmt_BiConsumer_ThrowsClosesQuery() {
        assertThrows(SQLException.class, () -> query.configureStatement((Throwables.BiConsumer<TestQuery, PreparedStatement, SQLException>) (q, stmt) -> {
            throw new SQLException("configureStatement BiConsumer failed");
        }));
    }

    @Test
    public void testConstructorRejectsNullStatement() {
        final IllegalArgumentException thrown = assertThrows(IllegalArgumentException.class, () -> new TestQuery(null));

        assertTrue(thrown.getMessage().contains("stmt"));
    }

    // Closed-query state is checked before input-parameter validation on execution methods.
    @Test
    @Tag("2025")
    public void testDelegatingOverloads_ClosedQueryValidationPriority() {
        query.close();

        assertThrows(IllegalStateException.class, () -> query.query((Class<?>) null));
        assertThrows(IllegalStateException.class, () -> query.list((Class<String>) null));
        assertThrows(IllegalStateException.class, () -> query.stream((Class<String>) null));
        assertThrows(IllegalStateException.class, () -> query.foreach((Consumer<DisposableObjArray>) null));
        assertThrows(IllegalStateException.class, () -> query.foreach(String.class, null));
    }

    @Test
    public void testExecutionFailureRemainsPrimaryWhenAutomaticCloseHandlerFails() throws SQLException {
        final SQLException executionFailure = new SQLException("query failed");
        final RuntimeException closeFailure = new RuntimeException("close handler failed");
        when(preparedStatement.executeQuery()).thenThrow(executionFailure);
        query.onClose(() -> {
            throw closeFailure;
        });

        final SQLException thrown = assertThrows(SQLException.class, query::queryForBoolean);

        assertSame(executionFailure, thrown);
        assertArrayEquals(new Throwable[] { closeFailure }, thrown.getSuppressed());
    }

    @Test
    public void testGetFetchSettings() throws SQLException {
        when(preparedStatement.getFetchSize()).thenReturn(128);
        when(preparedStatement.getFetchDirection()).thenReturn(ResultSet.FETCH_REVERSE);

        assertEquals(128, query.getFetchSize());
        assertEquals(ResultSet.FETCH_REVERSE, query.getFetchDirection());
    }

    @Test
    public void testNullOnCloseDoesNotRunCloseHandlersUnderQueryMonitor() {
        final CountDownLatch registrationFinished = new CountDownLatch(1);
        final AtomicReference<Throwable> registrationFailure = new AtomicReference<>();

        query.onClose(() -> {
            final Thread registration = new Thread(() -> {
                try {
                    assertThrows(IllegalStateException.class, () -> query.onClose(() -> {
                    }));
                } catch (final Throwable e) {
                    registrationFailure.set(e);
                } finally {
                    registrationFinished.countDown();
                }
            }, "registration-from-close-handler");

            registration.start();

            try {
                assertTrue(registrationFinished.await(1, TimeUnit.SECONDS), "close handler must not hold the monitor needed by another lifecycle call");
            } catch (final InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new AssertionError(e);
            }
        });

        assertThrows(IllegalArgumentException.class, () -> query.onClose(null));
        assertTrue(query.isClosed);
        assertNull(registrationFailure.get());
    }

    @Test
    public void testResultSetStreamExtractorFailureKeepsReusableDrainFailureSuppressed() throws SQLException {
        for (final boolean withColumnLabels : new boolean[] { false, true }) {
            final PreparedStatement statement = Mockito.mock(PreparedStatement.class);
            final ResultSet rs = Mockito.mock(ResultSet.class);
            final SQLException extractionFailure = new SQLException("result extraction failed");
            final SQLException drainFailure = new SQLException("remaining result drain failed");
            final TestQuery streamQuery = new TestQuery(statement).closeAfterExecution(false);
            when(statement.execute()).thenReturn(true);
            when(statement.getResultSet()).thenReturn(rs);
            when(statement.getMoreResults(Statement.KEEP_CURRENT_RESULT)).thenReturn(false);
            when(statement.getMoreResults()).thenThrow(drainFailure);
            when(rs.getMetaData()).thenReturn(Mockito.mock(ResultSetMetaData.class));
            final Jdbc.ResultExtractor<String> extractor = result -> {
                throw extractionFailure;
            };
            final Jdbc.BiResultExtractor<String> biExtractor = (result, labels) -> {
                throw extractionFailure;
            };

            try (Stream<String> results = withColumnLabels ? streamQuery.streamAllResultSets(biExtractor) : streamQuery.streamAllResultSets(extractor)) {
                final UncheckedSQLException thrown = assertThrows(UncheckedSQLException.class, results::toList);
                assertSame(extractionFailure, thrown.getCause());
                assertEquals(1, thrown.getSuppressed().length);
                assertTrue(thrown.getSuppressed()[0] instanceof UncheckedSQLException);
                assertSame(drainFailure, thrown.getSuppressed()[0].getCause());
            }

            verify(rs).close();
            verify(statement).getMoreResults();
            verify(statement, never()).close();
            assertFalse(streamQuery.isClosed);
        }
    }

    @Test
    public void testResultSetStreamReadFailureKeepsCloseHandlerFailureSuppressed() throws SQLException {
        for (final boolean withColumnLabels : new boolean[] { false, true }) {
            final PreparedStatement statement = Mockito.mock(PreparedStatement.class);
            final ResultSet rs = Mockito.mock(ResultSet.class);
            final SQLException readFailure = new SQLException("advancing result sets failed");
            final IllegalStateException closeFailure = new IllegalStateException("query close handler failed");
            final TestQuery streamQuery = new TestQuery(statement).onClose(() -> {
                throw closeFailure;
            });
            when(statement.execute()).thenReturn(true);
            when(statement.getResultSet()).thenReturn(rs);
            when(statement.getMoreResults(Statement.KEEP_CURRENT_RESULT)).thenThrow(readFailure);

            try (Stream<String> results = withColumnLabels ? streamQuery.streamAllResultSets((Jdbc.BiResultExtractor<String>) (result, labels) -> "value")
                    : streamQuery.streamAllResultSets((Jdbc.ResultExtractor<String>) result -> "value")) {
                final UncheckedSQLException thrown = assertThrows(UncheckedSQLException.class, results::toList);
                assertSame(readFailure, thrown.getCause());
                assertArrayEquals(new Throwable[] { closeFailure }, thrown.getSuppressed());
            }

            verify(rs).close();
            verify(statement).close();
            assertTrue(streamQuery.isClosed);
        }
    }

    @Test
    public void testResultSetStreamsRejectQueryClosedBeforeTraversal() throws SQLException {
        for (final boolean closeAfterExecution : new boolean[] { false, true }) {
            for (final boolean withColumnLabels : new boolean[] { false, true }) {
                final PreparedStatement statement = Mockito.mock(PreparedStatement.class);
                final TestQuery streamQuery = new TestQuery(statement).closeAfterExecution(closeAfterExecution);
                when(statement.getUpdateCount()).thenReturn(-1);

                try (Stream<String> results = withColumnLabels ? streamQuery.streamAllResultSets((Jdbc.BiResultExtractor<String>) (rs, labels) -> "value")
                        : streamQuery.streamAllResultSets((Jdbc.ResultExtractor<String>) rs -> "value")) {
                    streamQuery.close();

                    final IllegalStateException failure = assertThrows(IllegalStateException.class, results::toList);
                    assertTrue(failure.getMessage().contains("already been closed"));
                }

                verify(statement, never()).execute();
                verify(statement, never()).getMoreResults();
                verify(statement).close();
            }
        }
    }

    @Test
    public void testRowStreamCountSkipAndArrayKeepCleanupFailuresSuppressed() throws SQLException {
        for (int terminalVariant = 0; terminalVariant < 3; terminalVariant++) {
            final PreparedStatement statement = Mockito.mock(PreparedStatement.class);
            final ResultSet rs = Mockito.mock(ResultSet.class);
            final SQLException readFailure = new SQLException("row read failed");
            final IllegalStateException closeFailure = new IllegalStateException("query close handler failed");
            final TestQuery streamQuery = new TestQuery(statement).onClose(() -> {
                throw closeFailure;
            });
            when(statement.executeQuery()).thenReturn(rs);
            when(rs.next()).thenThrow(readFailure);
            final int operation = terminalVariant;

            try (Stream<String> rows = streamQuery.stream((Jdbc.RowMapper<String>) row -> "value")) {
                final UncheckedSQLException thrown = assertThrows(UncheckedSQLException.class, () -> {
                    switch (operation) {
                        case 0 -> rows.count();
                        case 1 -> rows.skip(2).toArray();
                        default -> rows.toArray(String[]::new);
                    }
                });

                assertSame(readFailure, thrown.getCause());
                assertArrayEquals(new Throwable[] { closeFailure }, thrown.getSuppressed());
            }

            verify(rs).close();
            verify(statement).close();
            assertTrue(streamQuery.isClosed);
        }
    }

    @Test
    public void testRowStreamFailuresKeepCleanupSuppressed() throws SQLException {
        for (final boolean failWhileReading : new boolean[] { false, true }) {
            for (int mapperVariant = 0; mapperVariant < 4; mapperVariant++) {
                final PreparedStatement statement = Mockito.mock(PreparedStatement.class);
                final ResultSet rs = Mockito.mock(ResultSet.class);
                final SQLException readFailure = new SQLException("row read failed");
                final IllegalArgumentException mapperFailure = new IllegalArgumentException("row mapping failed");
                final IllegalStateException closeFailure = new IllegalStateException("query close handler failed");
                final AtomicInteger closeHandlerCalls = new AtomicInteger();
                final TestQuery streamQuery = new TestQuery(statement).onClose(() -> {
                    closeHandlerCalls.incrementAndGet();
                    throw closeFailure;
                });
                when(statement.executeQuery()).thenReturn(rs);
                when(rs.getMetaData()).thenReturn(Mockito.mock(ResultSetMetaData.class));

                if (failWhileReading) {
                    when(rs.next()).thenThrow(readFailure);
                } else {
                    when(rs.next()).thenReturn(true, false);
                }

                final Jdbc.RowMapper<String> rowMapper = row -> {
                    throw mapperFailure;
                };
                final Jdbc.BiRowMapper<String> biRowMapper = (row, labels) -> {
                    throw mapperFailure;
                };

                try (Stream<String> rows = switch (mapperVariant) {
                    case 0 -> streamQuery.stream(rowMapper);
                    case 1 -> streamQuery.stream(biRowMapper);
                    case 2 -> streamQuery.stream(row -> true, rowMapper);
                    default -> streamQuery.stream((row, labels) -> true, biRowMapper);
                }) {
                    final RuntimeException thrown = assertThrows(RuntimeException.class, rows::toList);
                    if (failWhileReading) {
                        assertTrue(thrown instanceof UncheckedSQLException);
                        assertSame(readFailure, thrown.getCause());
                    } else {
                        assertSame(mapperFailure, thrown);
                    }
                    assertArrayEquals(new Throwable[] { closeFailure }, thrown.getSuppressed());
                }

                verify(rs).close();
                verify(statement).close();
                assertEquals(1, closeHandlerCalls.get());
                assertTrue(streamQuery.isClosed);
            }
        }
    }

    @Test
    public void testRowStreamsRejectQueryClosedBeforeTraversal() throws SQLException {
        for (final boolean closeAfterExecution : new boolean[] { false, true }) {
            for (int mapperVariant = 0; mapperVariant < 4; mapperVariant++) {
                final PreparedStatement statement = Mockito.mock(PreparedStatement.class);
                final TestQuery streamQuery = new TestQuery(statement).closeAfterExecution(closeAfterExecution);

                try (Stream<String> rows = switch (mapperVariant) {
                    case 0 -> streamQuery.stream((Jdbc.RowMapper<String>) rs -> "value");
                    case 1 -> streamQuery.stream((Jdbc.BiRowMapper<String>) (rs, labels) -> "value");
                    case 2 -> streamQuery.stream(rs -> true, rs -> "value");
                    default -> streamQuery.stream((rs, labels) -> true, (rs, labels) -> "value");
                }) {
                    streamQuery.close();

                    final IllegalStateException failure = assertThrows(IllegalStateException.class, rows::toList);
                    assertTrue(failure.getMessage().contains("already been closed"));
                }

                verify(statement, never()).getFetchDirection();
                verify(statement, never()).executeQuery();
                verify(statement).close();
            }
        }
    }

    @Test
    public void testRowStreamTerminalPathsRemainLazyAndCloseOnce() throws SQLException {
        for (final boolean closeAfterExecution : new boolean[] { false, true }) {
            for (int mapperVariant = 0; mapperVariant < 4; mapperVariant++) {
                for (int terminalVariant = 0; terminalVariant < 4; terminalVariant++) {
                    final PreparedStatement statement = Mockito.mock(PreparedStatement.class);
                    final ResultSet rs = Mockito.mock(ResultSet.class);
                    final AtomicInteger mappedRows = new AtomicInteger();
                    final AtomicInteger closeHandlerCalls = new AtomicInteger();
                    final TestQuery streamQuery = new TestQuery(statement).closeAfterExecution(closeAfterExecution).onClose(closeHandlerCalls::incrementAndGet);
                    when(statement.executeQuery()).thenReturn(rs);
                    when(rs.next()).thenReturn(true, true, true, false);
                    when(rs.getMetaData()).thenReturn(Mockito.mock(ResultSetMetaData.class));
                    final Jdbc.RowMapper<Integer> rowMapper = row -> mappedRows.incrementAndGet();
                    final Jdbc.BiRowMapper<Integer> biRowMapper = (row, labels) -> mappedRows.incrementAndGet();

                    try (Stream<Integer> rows = switch (mapperVariant) {
                        case 0 -> streamQuery.stream(rowMapper);
                        case 1 -> streamQuery.stream(biRowMapper);
                        case 2 -> streamQuery.stream(row -> true, rowMapper);
                        default -> streamQuery.stream((row, labels) -> true, biRowMapper);
                    }) {
                        Mockito.verifyNoInteractions(statement, rs);

                        switch (terminalVariant) {
                            case 0 -> assertEquals(3, rows.count());
                            case 1 -> assertArrayEquals(new Object[] { 3 }, rows.skip(2).toArray());
                            case 2 -> assertArrayEquals(new Integer[] { 1, 2, 3 }, rows.toArray(Integer[]::new));
                            default -> assertEquals(0, rows.limit(0).count());
                        }
                    }

                    assertEquals(terminalVariant == 3 ? 0 : 3, mappedRows.get());
                    verify(statement, times(terminalVariant == 3 ? 0 : 1)).executeQuery();
                    verify(rs, times(terminalVariant == 3 ? 0 : 4)).next();
                    verify(rs, times(terminalVariant == 3 ? 0 : 1)).close();
                    verify(statement, times(closeAfterExecution ? 1 : 0)).close();
                    assertEquals(closeAfterExecution ? 1 : 0, closeHandlerCalls.get());
                    assertEquals(closeAfterExecution, streamQuery.isClosed);
                    streamQuery.close();
                    verify(statement).close();
                    assertEquals(1, closeHandlerCalls.get());
                }
            }
        }
    }

    // Regression: the statement-config setters used to leave the query open when the driver failed. In the fluent
    // idiom `JdbcUtil.prepareQuery(ds, sql).setFetchSize(n)...` the caller never gets the query back, so the statement
    // (and, for DataSource-based queries, the pooled connection released by the close handler) leaked.
    @Test
    public void testStatementConfigSetters_DriverFailure_ClosesQueryAndRethrows() throws SQLException {
        final List<Throwables.Consumer<TestQuery, Exception>> setterCalls = Arrays.asList( //
                q -> q.setFetchDirection(FetchDirection.FORWARD), //
                TestQuery::setFetchDirectionToForward, //
                q -> q.setFetchSize(100), //
                q -> q.setMaxFieldSize(1024), //
                q -> q.setMaxRows(10), //
                q -> q.setLargeMaxRows(10L), //
                q -> q.setQueryTimeout(30));

        for (int i = 0; i < setterCalls.size(); i++) {
            final PreparedStatement stmt = Mockito.mock(PreparedStatement.class);
            final SQLException driverFailure = new SQLException("driver rejected setter " + i);
            doThrow(driverFailure).when(stmt).setFetchDirection(anyInt());
            doThrow(driverFailure).when(stmt).setFetchSize(anyInt());
            doThrow(driverFailure).when(stmt).setMaxFieldSize(anyInt());
            doThrow(driverFailure).when(stmt).setMaxRows(anyInt());
            doThrow(driverFailure).when(stmt).setLargeMaxRows(anyLong());
            doThrow(driverFailure).when(stmt).setQueryTimeout(anyInt());

            final TestQuery q = new TestQuery(stmt);
            final AtomicInteger closeHandlerCalls = new AtomicInteger();
            q.onClose(closeHandlerCalls::incrementAndGet);

            final Throwables.Consumer<TestQuery, Exception> setterCall = setterCalls.get(i);
            final SQLException thrown = assertThrows(SQLException.class, () -> setterCall.accept(q));

            assertSame(driverFailure, thrown, "setter " + i);
            verify(stmt).close();
            assertEquals(1, closeHandlerCalls.get(), "close handler (e.g. connection release) must run for setter " + i);
            assertThrows(IllegalStateException.class, q::query, "query must be closed after failing setter " + i);
        }
    }

    // Statement settings, batch staging, configuration, and execution retain the query-state check.
    @Test
    @Tag("2025")
    public void testStatementOperations_ClosedQuery_ThrowIllegalState() throws SQLException {
        query.close();

        assertThrows(IllegalStateException.class, () -> query.setFetchSize(100));
        assertThrows(IllegalStateException.class, () -> query.setMaxRows(10));
        assertThrows(IllegalStateException.class, () -> query.setLargeMaxRows(10L));
        assertThrows(IllegalStateException.class, () -> query.setMaxFieldSize(256));
        assertThrows(IllegalStateException.class, () -> query.setQueryTimeout(5));
        assertThrows(IllegalStateException.class, () -> query.setFetchDirection(FetchDirection.FORWARD));
        assertThrows(IllegalStateException.class, () -> query.addBatch());
        assertThrows(IllegalStateException.class, () -> query.configureStatement(stmt -> stmt.setPoolable(false)));

        // Execution methods keep the fail-fast IllegalStateException.
        assertThrows(IllegalStateException.class, () -> query.exists());
        assertThrows(IllegalStateException.class, () -> query.update());
        assertThrows(IllegalStateException.class, () -> query.list());
    }
}
