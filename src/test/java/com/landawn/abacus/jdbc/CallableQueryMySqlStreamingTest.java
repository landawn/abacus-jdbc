package com.landawn.abacus.jdbc;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.CALLS_REAL_METHODS;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.lang.reflect.Field;
import java.sql.CallableStatement;
import java.sql.DatabaseMetaData;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Types;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.concurrent.locks.ReentrantLock;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import com.landawn.abacus.util.Tuple.Tuple2;
import com.mysql.cj.Query;
import com.mysql.cj.conf.RuntimeProperty;
import com.mysql.cj.exceptions.CJException;
import com.mysql.cj.jdbc.JdbcConnection;
import com.mysql.cj.jdbc.StatementImpl;
import com.mysql.cj.jdbc.result.ResultSetImpl;
import com.mysql.cj.protocol.Resultset;
import com.mysql.cj.protocol.ResultsetRows;

@Tag("2025")
class CallableQueryMySqlStreamingTest {

    @ParameterizedTest(name = "labels={0}, fullRead={1}, streaming={2}")
    @CsvSource({ "false,false,true", "false,true,true", "true,false,true", "true,true,true", "false,false,false", "false,true,false", "true,false,false",
            "true,true,false" })
    void extractsOnlyTheFirstResultAndReleasesTheRemainingResults(final boolean labels, final boolean fullRead, final boolean streaming) throws Exception {
        final Fixture fixture = new Fixture(streaming, 4, 3);
        fixture.startWithFirstResult();
        final CallableQuery query = new CallableQuery(fixture.callable);

        final Tuple2<Integer, Jdbc.OutParamResult> result = extract(query, labels, rs -> {
            assertEquals(0, fixture.cursors.get(0).position, "The first result must reach the extractor unread");
            assertEquals(ResultSet.FETCH_FORWARD, fixture.fetchDirection);
            return read(rs, fullRead);
        });

        assertEquals(fullRead ? 4 : 1, result._1.intValue());
        assertNotNull(result._2);
        assertEquals(streaming || fullRead ? 4 : 1, fixture.cursors.get(0).position);
        assertEquals(streaming ? 3 : 0, fixture.cursors.get(1).position, "Buffered trailing results need closing, not row reads");
        assertEquals(!streaming, fixture.cursors.get(0).result.isClosed());
        assertTrue(fixture.cursors.get(1).result.isClosed());
        fixture.assertExhausted();
        assertEquals(ResultSet.FETCH_REVERSE, fixture.fetchDirection, "Restore the original statement setting on query close");
        verify(fixture.callable).close();
    }

    @ParameterizedTest(name = "labels={0}, resultCount={1}")
    @CsvSource({ "false,0", "true,0", "false,1", "true,1", "false,3", "true,3" })
    void handlesNoOneOrSeveralResultsInterleavedWithUpdateCounts(final boolean labels, final int resultCount) throws Exception {
        final int[] rowCounts = new int[resultCount];
        java.util.Arrays.fill(rowCounts, 2);
        final Fixture fixture = new Fixture(true, rowCounts);
        final CallableQuery query = new CallableQuery(fixture.callable);

        final Tuple2<Integer, Jdbc.OutParamResult> result = extract(query, labels, rs -> read(rs, false));

        if (resultCount == 0) {
            assertNull(result._1);
        } else {
            assertEquals(1, result._1.intValue());
            assertTrue(fixture.cursors.get(resultCount - 1).result.isClosed());
        }
        assertNotNull(result._2);
        for (final Cursor cursor : fixture.cursors) {
            assertEquals(2, cursor.position);
        }
        fixture.assertExhausted();
        verify(fixture.callable).close();
    }

    @ParameterizedTest(name = "labels={0}, failureResult={1}")
    @CsvSource({ "false,0", "true,0", "false,1", "true,1" })
    void propagatesAProcedureErrorAtTheFirstOrLaterResultEnd(final boolean labels, final int failureResult) throws Exception {
        final Fixture fixture = new Fixture(true, 3, 2);
        final SQLException failure = new SQLException("procedure failed at EOF", "45000");
        fixture.cursors.get(failureResult).eofFailure = failure;
        final CallableQuery query = new CallableQuery(fixture.callable);

        assertSame(failure, assertThrows(SQLException.class, () -> extract(query, labels, rs -> read(rs, false))));
        verify(fixture.callable).close();
    }

    @ParameterizedTest(name = "labels={0}, failureResult={1}")
    @CsvSource({ "false,0", "true,0", "false,1", "true,1" })
    void keepsExtractorFailurePrimaryAndSuppressesTheDrainError(final boolean labels, final int failureResult) throws Exception {
        final Fixture fixture = new Fixture(true, 3, 2);
        final SQLException drainFailure = new SQLException("procedure failed while cleaning up", "45000");
        fixture.cursors.get(failureResult).eofFailure = drainFailure;
        final IllegalStateException extractionFailure = new IllegalStateException("extractor failed");
        final CallableQuery query = new CallableQuery(fixture.callable);

        final IllegalStateException thrown = assertThrows(IllegalStateException.class, () -> extract(query, labels, rs -> {
            assertTrue(rs.next());
            throw extractionFailure;
        }));

        assertSame(extractionFailure, thrown);
        assertTrue(hasSuppressed(thrown, drainFailure), "Preserve the SQL drain failure in the primary exception's suppressed chain");
        verify(fixture.callable).close();
    }

    @ParameterizedTest
    @ValueSource(booleans = { false, true })
    void extractorFailureStillDrainsAllRemainingStreamingRows(final boolean labels) throws Exception {
        final Fixture fixture = new Fixture(true, 3, 2);
        final IllegalStateException failure = new IllegalStateException("extractor failed");
        final CallableQuery query = new CallableQuery(fixture.callable);

        assertSame(failure, assertThrows(IllegalStateException.class, () -> extract(query, labels, rs -> {
            assertTrue(rs.next());
            throw failure;
        })));

        assertEquals(0, failure.getSuppressed().length);
        assertEquals(3, fixture.cursors.get(0).position);
        assertEquals(2, fixture.cursors.get(1).position);
        assertTrue(fixture.cursors.get(1).result.isClosed());
        fixture.assertExhausted();
        verify(fixture.callable).close();
    }

    @ParameterizedTest
    @ValueSource(booleans = { false, true })
    void skipsAnInitialNullResultClaimBeforeExtractingAndReadingBufferedOutParameters(final boolean labels) throws SQLException {
        final CallableStatement statement = mock(CallableStatement.class);
        final ResultSet resultSet = mock(ResultSet.class);
        final ResultSetMetaData metadata = mock(ResultSetMetaData.class);
        when(statement.execute()).thenReturn(true);
        when(statement.getResultSet()).thenReturn(null, resultSet);
        when(statement.getUpdateCount()).thenReturn(-1);
        when(statement.getMoreResults()).thenReturn(true, false);
        when(statement.getInt(1)).thenReturn(42);
        when(resultSet.next()).thenReturn(true, false);
        when(resultSet.getMetaData()).thenReturn(metadata);
        when(metadata.getColumnCount()).thenReturn(1);
        when(metadata.getColumnLabel(1)).thenReturn("value");
        final CallableQuery query = new CallableQuery(statement).registerOutParameter(1, Types.INTEGER);

        final Tuple2<Integer, Jdbc.OutParamResult> result = extract(query, labels, rs -> read(rs, true));

        assertEquals(1, result._1.intValue());
        assertEquals(Integer.valueOf(42), result._2.getOutParamValue(1));
        final org.mockito.InOrder order = org.mockito.Mockito.inOrder(statement, resultSet);
        order.verify(statement).execute();
        order.verify(statement).getResultSet();
        order.verify(statement).getMoreResults();
        order.verify(statement).getResultSet();
        order.verify(resultSet).close();
        order.verify(statement).getMoreResults();
        order.verify(statement).getInt(1);
        order.verify(statement).close();
    }

    @ParameterizedTest
    @ValueSource(booleans = { false, true })
    void drainsBeforeReusingTheQueryAndRestoresFetchDirectionOnExplicitClose(final boolean labels) throws Exception {
        final Fixture fixture = new Fixture(true, 2, 3);
        final CallableQuery query = new CallableQuery(fixture.callable).closeAfterExecution(false);
        try {
            assertEquals(1, extract(query, labels, rs -> read(rs, false))._1.intValue());
            fixture.assertExhausted();
            verify(fixture.callable, never()).close();
            assertEquals(ResultSet.FETCH_FORWARD, fixture.fetchDirection);

            fixture.reset(4);
            assertEquals(4, extract(query, labels, rs -> read(rs, true))._1.intValue());
            fixture.assertExhausted();
            verify(fixture.callable, never()).close();
        } finally {
            query.close();
        }

        verify(fixture.callable, times(2)).execute();
        verify(fixture.callable).close();
        assertEquals(ResultSet.FETCH_REVERSE, fixture.fetchDirection);
    }

    @ParameterizedTest
    @ValueSource(booleans = { false, true })
    void preservesAnExplicitFetchDirection(final boolean labels) throws Exception {
        final Fixture fixture = new Fixture(true, 2);
        final CallableQuery query = new CallableQuery(fixture.callable).setFetchDirection(FetchDirection.REVERSE);

        assertEquals(1, extract(query, labels, rs -> {
            assertEquals(ResultSet.FETCH_REVERSE, fixture.fetchDirection);
            return read(rs, false);
        })._1.intValue());

        verify(fixture.callable, never()).setFetchDirection(ResultSet.FETCH_FORWARD);
        fixture.assertExhausted();
    }

    private static Tuple2<Integer, Jdbc.OutParamResult> extract(final CallableQuery query, final boolean labels, final Jdbc.ResultExtractor<Integer> extractor)
            throws SQLException {
        // Streaming Connector/J does not support registered OUT parameters, so only buffered fixtures register them.
        return labels ? query.queryAndGetOutParameters((rs, names) -> {
            assertEquals(List.of("value"), names);
            return extractor.apply(rs);
        }) : query.queryAndGetOutParameters(extractor);
    }

    private static int read(final ResultSet rs, final boolean all) throws SQLException {
        int count = 0;
        while (rs.next()) {
            count++;
            if (!all) {
                break;
            }
        }
        return count;
    }

    private static boolean hasSuppressed(final Throwable primary, final Throwable expected) {
        for (final Throwable suppressed : primary.getSuppressed()) {
            if (suppressed == expected || suppressed.getCause() == expected || hasSuppressed(suppressed, expected)) {
                return true;
            }
        }
        return false;
    }

    // Real Connector/J result close, statement notification and advancement; simulated execution, rows and metadata.
    // Closing an earlier streamed driver result reproduces the close-time SET failure while its successor is active.
    private static final class Fixture {
        final JdbcConnection connection = mock(JdbcConnection.class);
        final StatementImpl driver = mock(StatementImpl.class, CALLS_REAL_METHODS);
        final CallableStatement callable = mock(CallableStatement.class);
        final List<Cursor> cursors = new ArrayList<>();
        final boolean streaming;
        int fetchDirection = ResultSet.FETCH_REVERSE;
        boolean closed;

        Fixture(final boolean streaming, final int... rowCounts) throws Exception {
            this.streaming = streaming;
            when(connection.getConnectionLock()).thenReturn(new ReentrantLock());
            final DatabaseMetaData metadata = mock(DatabaseMetaData.class);
            when(metadata.supportsMultipleOpenResults()).thenReturn(true);
            when(metadata.getDriverName()).thenReturn(com.mysql.cj.Constants.CJ_NAME);
            when(connection.getMetaData()).thenReturn(metadata);

            final Query query = mock(Query.class);
            when(query.getResultType()).thenReturn(Resultset.Type.FORWARD_ONLY);
            when(query.getResultFetchSize()).thenReturn(streaming ? Integer.MIN_VALUE : 0);
            @SuppressWarnings("unchecked")
            final RuntimeProperty<Boolean> dontTrack = mock(RuntimeProperty.class);
            when(dontTrack.getValue()).thenReturn(false);
            set(driver, "connection", connection);
            set(driver, "query", query);
            set(driver, "resultSetConcurrency", ResultSet.CONCUR_READ_ONLY);
            set(driver, "dontTrackOpenResources", dontTrack);
            set(driver, "openResultSets", new HashSet<>());

            when(callable.getConnection()).thenReturn(connection);
            when(callable.getFetchSize()).thenAnswer(invocation -> driver.getFetchSize());
            when(callable.getResultSetType()).thenAnswer(invocation -> driver.getResultSetType());
            when(callable.getResultSetConcurrency()).thenAnswer(invocation -> driver.getResultSetConcurrency());
            when(callable.getResultSet()).thenAnswer(invocation -> driver.getResultSet());
            when(callable.getMoreResults()).thenAnswer(invocation -> driver.getMoreResults());
            when(callable.getMoreResults(anyInt())).thenAnswer(invocation -> driver.getMoreResults(invocation.getArgument(0)));
            when(callable.getUpdateCount()).thenAnswer(invocation -> driver.getUpdateCount());
            when(callable.execute()).thenAnswer(invocation -> driver.getResultSet() != null);
            when(callable.getFetchDirection()).thenAnswer(invocation -> fetchDirection);
            doAnswer(invocation -> {
                fetchDirection = invocation.getArgument(0);
                return null;
            }).when(callable).setFetchDirection(anyInt());
            when(callable.isClosed()).thenAnswer(invocation -> closed);
            doAnswer(invocation -> {
                closed = true;
                return null;
            }).when(callable).close();
            reset(rowCounts);
        }

        void reset(final int... rowCounts) throws Exception {
            cursors.clear();
            ResultSetImpl next = updateCount(0, null);
            for (int i = rowCounts.length - 1; i >= 0; i--) {
                final Cursor cursor = new Cursor(this, rowCounts[i], next);
                cursors.add(0, cursor);
                next = updateCount(7, cursor.result);
            }
            set(driver, "results", next);
            set(driver, "updateCount", next.getUpdateCount());
        }

        void startWithFirstResult() throws Exception {
            set(driver, "results", cursors.get(0).result);
            set(driver, "updateCount", -1L);
        }

        private ResultSetImpl updateCount(final long count, final ResultSetImpl next) throws Exception {
            final ResultSetImpl result = mock(ResultSetImpl.class, CALLS_REAL_METHODS);
            set(result, "lock", new ReentrantLock());
            set(result, "connection", connection);
            set(result, "owningStatement", driver);
            set(result, "updateCount", count);
            result.setNextResultset(next);
            return result;
        }

        void assertExhausted() throws SQLException {
            assertNull(driver.getResultSet());
            assertEquals(-1, driver.getUpdateCount());
            assertEquals(0, driver.getOpenResultSetCount());
        }
    }

    private static final class Cursor {
        final ResultSetImpl result = mock(ResultSetImpl.class, CALLS_REAL_METHODS);
        final ResultSetImpl successor;
        final int rowCount;
        int position;
        boolean eof;
        SQLException eofFailure;

        Cursor(final Fixture fixture, final int rowCount, final ResultSetImpl successor) throws Exception {
            this.rowCount = rowCount;
            this.successor = successor;
            set(result, "lock", new ReentrantLock());
            set(result, "connection", fixture.connection);
            set(result, "owningStatement", fixture.driver);
            final ResultSetMetaData metadata = mock(ResultSetMetaData.class);
            when(metadata.getColumnCount()).thenReturn(1);
            when(metadata.getColumnLabel(1)).thenReturn("value");
            doAnswer(invocation -> metadata).when(result).getMetaData();
            doAnswer(invocation -> readNext()).when(result).next();

            final ResultsetRows rows = mock(ResultsetRows.class);
            doAnswer(invocation -> {
                if (fixture.streaming) {
                    while (readNext()) {
                        // Discover subsequent results while draining, as the driver does at EOF.
                    }
                    final int index = fixture.cursors.indexOf(this);
                    if (result.getNextResultset() != null
                            && fixture.cursors.subList(index + 1, fixture.cursors.size()).stream().anyMatch(cursor -> !cursor.eof)) {
                        throw new CJException("Cannot restore streaming session settings while a later result streams");
                    }
                }
                return null;
            }).when(rows).close();
            set(result, "rowData", rows);
            if (!fixture.streaming) {
                result.setNextResultset(successor);
            }
        }

        private boolean readNext() throws SQLException {
            if (result.isClosed()) {
                throw new SQLException("ResultSet is closed");
            }
            if (eof) {
                return false;
            }
            if (position < rowCount) {
                position++;
                return true;
            }
            eof = true;
            if (eofFailure != null) {
                // Connector/J marks the stream exhausted after a server error; repeating next() does not rethrow it.
                throw eofFailure;
            }
            result.setNextResultset(successor);
            return false;
        }
    }

    private static void set(final Object target, final String name, final Object value) throws Exception {
        for (Class<?> type = target.getClass(); type != null; type = type.getSuperclass()) {
            try {
                final Field field = type.getDeclaredField(name);
                field.setAccessible(true);
                field.set(target, value);
                return;
            } catch (final NoSuchFieldException ignored) {
                // Driver row and update-count state is inherited from NativeResultset.
            }
        }
        throw new NoSuchFieldException(name);
    }
}
