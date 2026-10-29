package com.landawn.abacus.jdbc;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.CALLS_REAL_METHODS;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.lang.reflect.Field;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.HashSet;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.ReentrantLock;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;

import com.landawn.abacus.exception.UncheckedSQLException;
import com.landawn.abacus.util.stream.ObjIteratorEx;
import com.mysql.cj.Query;
import com.mysql.cj.conf.RuntimeProperty;
import com.mysql.cj.jdbc.JdbcConnection;
import com.mysql.cj.jdbc.StatementImpl;
import com.mysql.cj.jdbc.result.ResultSetInternalMethods;
import com.mysql.cj.protocol.Resultset;

@Tag("2025")
class JdbcUtilResultAdvanceTest {

    // Exercise the pinned Connector/J advancement implementation with a simulated cursor, without a server.
    @ParameterizedTest
    @ValueSource(booleans = { true, false })
    void connectorJDeliversEveryRowBeforeAdvancing(final boolean streaming) throws Exception {
        final JdbcConnection connection = mock(JdbcConnection.class);
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

        final ResultSetInternalMethods cursor = mock(ResultSetInternalMethods.class);
        when(cursor.hasRows()).thenReturn(true);
        final AtomicInteger rowAdvances = new AtomicInteger();
        when(cursor.next()).thenAnswer(invocation -> rowAdvances.incrementAndGet() <= 100);

        final StatementImpl statement = mock(StatementImpl.class, CALLS_REAL_METHODS);
        set(statement, "connection", connection);
        set(statement, "query", query);
        set(statement, "resultSetConcurrency", ResultSet.CONCUR_READ_ONLY);
        set(statement, "dontTrackOpenResources", dontTrack);
        set(statement, "openResultSets", new HashSet<>());
        set(statement, "results", cursor);

        final ObjIteratorEx<ResultSet> iterator = JdbcUtil.iterateAllResultSets(statement, true);
        try (ResultSet delivered = iterator.next()) {
            assertEquals(0, rowAdvances.get(), "advancement must not drain rows before delivery");
            int rows = 0;
            while (delivered.next()) {
                rows++;
            }
            assertEquals(100, rows);
            assertFalse(iterator.hasNext());
            if (streaming) {
                // A streamed result is read to its end and released by the advance; Connector/J cannot close it while
                // streaming a successor. The last one is closed once no results remain, restoring the session.
                verify(statement).getMoreResults();
                verify(statement, never()).getMoreResults(Statement.KEEP_CURRENT_RESULT);
                verify(cursor).close();
            } else {
                verify(statement).getMoreResults(Statement.KEEP_CURRENT_RESULT);
                verify(cursor, never()).close();
            }
        } finally {
            iterator.closeResource();
        }
        verify(cursor).close();
    }

    // Connector/J 8.0 and later report "MySQL Connector/J"; 5.1 and 6.0 report "MySQL Connector Java".
    @ParameterizedTest
    @ValueSource(strings = { "MySQL Connector/J", "MySQL Connector Java" })
    void connectorJStreamingIsIdentifiedForEveryDriverGeneration(final String driverName) throws Exception {
        assertTrue(JdbcUtil.usesMySqlStreamingResults(streamingStatement(driverName)));
    }

    // Only the last streamed result is closed, once no results remain: earlier ones are released by the advance.
    @ParameterizedTest
    @ValueSource(booleans = { true, false })
    void connectorJStreamingAdvanceReadsDeliveredResultToEndWithoutClosingIt(final boolean closedByCaller) throws Exception {
        final Statement statement = streamingStatement("MySQL Connector Java");
        final ResultSet first = mock(ResultSet.class);
        final ResultSet second = mock(ResultSet.class);
        when(first.next()).thenReturn(true, true, false);
        when(statement.getResultSet()).thenReturn(first, second);
        when(statement.getMoreResults()).thenReturn(true, false);
        when(statement.getUpdateCount()).thenReturn(-1);

        final ObjIteratorEx<ResultSet> iterator = JdbcUtil.iterateAllResultSets(statement, true);
        try {
            final ResultSet delivered = iterator.next();
            assertTrue(delivered.next());
            if (closedByCaller) {
                delivered.close();
                assertTrue(delivered.isClosed());
            }

            assertTrue(iterator.hasNext());
            final org.mockito.InOrder order = org.mockito.Mockito.inOrder(first, second, statement);
            order.verify(first, times(3)).next();
            order.verify(statement).getMoreResults();
            iterator.next().close();
            verify(second, never()).close();
            assertFalse(iterator.hasNext());
            order.verify(statement).getMoreResults();
            order.verify(second).close();
        } finally {
            iterator.closeResource();
        }

        verify(first, never()).close();
        verify(second).close();
        verify(statement, never()).getMoreResults(Statement.KEEP_CURRENT_RESULT);
    }

    // Closing the statement does not read later streamed results, and Connector/J rejects every later statement on the
    // connection until they are read. Closing the iterator early reads each to its end before advancing past it.
    @Test
    void connectorJStreamingEarlyCloseReadsEveryRemainingResultBeforeAdvancing() throws Exception {
        final Statement statement = streamingStatement("MySQL Connector/J");
        final ResultSet first = mock(ResultSet.class);
        final ResultSet second = mock(ResultSet.class);
        // The caller reads one row, closing the wrapper reads to the end, and the discard reads past the end once more.
        when(first.next()).thenReturn(true, false);
        when(second.next()).thenReturn(true, true, false);
        // Delivery, then the discard reads the current (delivered) result and the one after it.
        when(statement.getResultSet()).thenReturn(first, first, second);
        when(statement.getMoreResults()).thenReturn(true, false);
        when(statement.getUpdateCount()).thenReturn(-1);

        final ObjIteratorEx<ResultSet> iterator = JdbcUtil.iterateAllResultSets(statement, true);
        try (ResultSet delivered = iterator.next()) {
            assertTrue(delivered.next());
        } finally {
            iterator.closeResource();
        }

        final org.mockito.InOrder order = org.mockito.Mockito.inOrder(first, second, statement);
        order.verify(first, times(3)).next();
        order.verify(statement).getMoreResults();
        order.verify(second, times(3)).next();
        order.verify(statement).getMoreResults();
        // Only the last result read is closed, once no results remain.
        order.verify(second).close();
        verify(first, never()).close();
        verify(second).close();
        assertFalse(iterator.hasNext());
    }

    // Connector/J reports an error a procedure raises after a SELECT (e.g. SIGNAL) on the read that reaches that SELECT's
    // end. When the extractor stops early, closing the streaming wrapper does that read, so its failure must not be lost.
    @Test
    void connectorJStreamingExtractionReportsReadFailureAfterEarlyReturn() throws Exception {
        final ResultSet delegate = mock(ResultSet.class);
        final SQLException signal = new SQLException("procedure failed", "45000");
        when(delegate.next()).thenReturn(true).thenThrow(signal);

        final ResultSet rs = ResultSetProxy.wrap(delegate, true);
        assertSame(signal, assertThrows(SQLException.class, () -> JdbcUtil.extractAndCloseResultSet(rs, (Jdbc.ResultExtractor<Boolean>) ResultSet::next)));
        assertSame(signal,
                assertThrows(SQLException.class, () -> JdbcUtil.extractAndCloseResultSet(ResultSetProxy.wrap(delegateFailingAfterOneRow(signal), true),
                        (Jdbc.BiResultExtractor<Boolean>) (r, labels) -> r.next())));
    }

    @Test
    void connectorJStreamingExtractionKeepsExtractorFailurePrimary() throws Exception {
        final SQLException signal = new SQLException("procedure failed", "45000");
        final SQLException extractorFailure = new SQLException("extractor failed");

        final SQLException thrown = assertThrows(SQLException.class,
                () -> JdbcUtil.extractAndCloseResultSet(ResultSetProxy.wrap(delegateFailingAfterOneRow(signal), true), (Jdbc.ResultExtractor<Object>) r -> {
                    r.next();
                    throw extractorFailure;
                }));

        assertSame(extractorFailure, thrown);
        org.junit.jupiter.api.Assertions.assertArrayEquals(new Throwable[] { signal }, thrown.getSuppressed());
    }

    // Closing an ordinary result set after extraction stays quiet: its failure says nothing about the statement's outcome.
    @Test
    void ordinaryExtractionStillClosesQuietly() throws Exception {
        final ResultSet delegate = mock(ResultSet.class);
        org.mockito.Mockito.doThrow(new SQLException("close failed")).when(delegate).close();

        assertEquals(Boolean.FALSE, JdbcUtil.extractAndCloseResultSet(ResultSetProxy.wrap(delegate), (Jdbc.ResultExtractor<Boolean>) ResultSet::next));
        verify(delegate).close();
    }

    @Test
    void connectorJStreamingEarlyCloseReportsReadFailure() throws Exception {
        final Statement statement = streamingStatement("MySQL Connector/J");
        final ResultSet first = mock(ResultSet.class);
        final ResultSet second = mock(ResultSet.class);
        final SQLException signal = new SQLException("procedure failed", "45000");
        when(second.next()).thenThrow(signal);
        when(statement.getResultSet()).thenReturn(first, first, second);
        when(statement.getMoreResults()).thenReturn(true, false);
        when(statement.getUpdateCount()).thenReturn(-1);

        final ObjIteratorEx<ResultSet> iterator = JdbcUtil.iterateAllResultSets(statement, true);
        iterator.next().close();

        assertSame(signal, assertThrows(UncheckedSQLException.class, iterator::closeResource).getCause());
        iterator.closeResource();
        assertFalse(iterator.hasNext());
    }

    // A statement the caller already closed has no results left to read, and reading would only fail.
    @Test
    void connectorJStreamingEarlyCloseSkipsClosedStatement() throws Exception {
        final Statement statement = streamingStatement("MySQL Connector/J");
        final ResultSet first = mock(ResultSet.class);
        when(statement.getResultSet()).thenReturn(first);
        when(statement.getUpdateCount()).thenReturn(-1);

        final ObjIteratorEx<ResultSet> iterator = JdbcUtil.iterateAllResultSets(statement, true);
        iterator.next().close();
        when(statement.isClosed()).thenReturn(true);

        iterator.closeResource();

        verify(statement, times(1)).getResultSet();
        verify(statement, never()).getMoreResults();
    }

    // query2ResultSets reads only two of three results; the error raised after the third surfaces as a checked SQLException.
    @Test
    void connectorJStreamingQueryReportsErrorAfterUnconsumedResults() throws Exception {
        final java.sql.PreparedStatement statement = mock(java.sql.PreparedStatement.class);
        final Connection connection = mock(Connection.class);
        final DatabaseMetaData metadata = mock(DatabaseMetaData.class);
        when(statement.getFetchSize()).thenReturn(Integer.MIN_VALUE);
        when(statement.getResultSetType()).thenReturn(ResultSet.TYPE_FORWARD_ONLY);
        when(statement.getResultSetConcurrency()).thenReturn(ResultSet.CONCUR_READ_ONLY);
        when(statement.getConnection()).thenReturn(connection);
        when(connection.getMetaData()).thenReturn(metadata);
        when(metadata.getDriverName()).thenReturn("MySQL Connector/J");

        final ResultSet first = resultWithoutColumns();
        final ResultSet second = resultWithoutColumns();
        final ResultSet third = mock(ResultSet.class);
        final SQLException signal = new SQLException("procedure failed", "45000");
        when(third.next()).thenThrow(signal);
        when(statement.execute()).thenReturn(true);
        // Delivery of the first two results, then the discard reads the second (current) one and the third.
        when(statement.getResultSet()).thenReturn(first, second, second, third);
        when(statement.getMoreResults()).thenReturn(true, true);
        when(statement.getUpdateCount()).thenReturn(-1);

        final PreparedQuery query = new PreparedQuery(statement);
        final SQLException thrown = assertThrows(SQLException.class, () -> query.query2ResultSets((rs, labels) -> rs.next(), (rs, labels) -> rs.next()));

        assertSame(signal, thrown);
        verify(statement).close();
    }

    private static ResultSet delegateFailingAfterOneRow(final SQLException failure) throws SQLException {
        final ResultSet delegate = resultWithoutColumns();
        when(delegate.next()).thenReturn(true).thenThrow(failure);
        return delegate;
    }

    // Bi-extractors read the column labels first.
    private static ResultSet resultWithoutColumns() throws SQLException {
        final ResultSet result = mock(ResultSet.class);
        when(result.getMetaData()).thenReturn(mock(java.sql.ResultSetMetaData.class));
        return result;
    }

    private static Statement streamingStatement(final String driverName) throws SQLException {
        final Statement statement = mock(Statement.class);
        final Connection connection = mock(Connection.class);
        final DatabaseMetaData metadata = mock(DatabaseMetaData.class);
        when(statement.getFetchSize()).thenReturn(Integer.MIN_VALUE);
        when(statement.getResultSetType()).thenReturn(ResultSet.TYPE_FORWARD_ONLY);
        when(statement.getResultSetConcurrency()).thenReturn(ResultSet.CONCUR_READ_ONLY);
        when(statement.getConnection()).thenReturn(connection);
        when(connection.getMetaData()).thenReturn(metadata);
        when(metadata.getDriverName()).thenReturn(driverName);
        return statement;
    }

    @Test
    void deferredAdvancePreservesMultipleOpenResultsAndDoesNotAdvanceTwiceOnHasNext() throws Exception {
        final Statement statement = mock(Statement.class);
        final ResultSet first = mock(ResultSet.class);
        final ResultSet second = mock(ResultSet.class);
        when(statement.getResultSet()).thenReturn(first, second);
        when(statement.getMoreResults(Statement.KEEP_CURRENT_RESULT)).thenReturn(true, false);
        when(statement.getUpdateCount()).thenReturn(-1);

        final ObjIteratorEx<ResultSet> iterator = JdbcUtil.iterateAllResultSets(statement, true);
        try (ResultSet deliveredFirst = iterator.next()) {
            verify(statement, never()).getMoreResults(Statement.KEEP_CURRENT_RESULT);
            assertTrue(iterator.hasNext());
            assertTrue(iterator.hasNext());
            verify(statement).getMoreResults(Statement.KEEP_CURRENT_RESULT);
            verify(first, never()).close();
            deliveredFirst.next();
            verify(first).next();
            try (ResultSet deliveredSecond = iterator.next()) {
                deliveredSecond.next();
                verify(second).next();
                assertFalse(iterator.hasNext());
                verify(statement, times(2)).getMoreResults(Statement.KEEP_CURRENT_RESULT);
                verify(statement, never()).getMoreResults();
            }
        } finally {
            iterator.closeResource();
        }
    }

    // Simulate a permissive non-Connector/J driver that reports the sentinel. Actual H2 rejects negative fetch sizes.
    @ParameterizedTest
    @NullSource
    @ValueSource(strings = { "H2 JDBC Driver", "MariaDB Connector/J", "custom JDBC driver", "" })
    void matchingSettingsWithoutConnectorJIdentificationDoNotDrainOnClose(final String driverName) throws Exception {
        final Statement statement = mock(Statement.class);
        final Connection connection = mock(Connection.class);
        final DatabaseMetaData metadata = mock(DatabaseMetaData.class);
        final ResultSet result = mock(ResultSet.class);
        when(statement.getFetchSize()).thenReturn(Integer.MIN_VALUE);
        when(statement.getResultSetType()).thenReturn(ResultSet.TYPE_FORWARD_ONLY);
        when(statement.getResultSetConcurrency()).thenReturn(ResultSet.CONCUR_READ_ONLY);
        when(statement.getConnection()).thenReturn(connection);
        when(connection.getMetaData()).thenReturn(metadata);
        when(metadata.getDriverName()).thenReturn(driverName);
        when(statement.getResultSet()).thenReturn(result);

        final ObjIteratorEx<ResultSet> iterator = JdbcUtil.iterateAllResultSets(statement, true);
        try {
            iterator.next().close();
        } finally {
            iterator.closeResource();
        }

        verify(result).close();
        verify(result, never()).next();
        verify(statement, never()).getMoreResults();
    }

    @ParameterizedTest
    @ValueSource(strings = { "connection", "metadata", "driverName" })
    void identificationFailureDoesNotPreventResultDeliveryOrForceDraining(final String failurePoint) throws Exception {
        final Statement statement = statementWithIdentificationFailure(failurePoint);
        final ResultSet result = mock(ResultSet.class);
        when(statement.getResultSet()).thenReturn(result);
        when(result.next()).thenReturn(true, false);

        final ObjIteratorEx<ResultSet> iterator = JdbcUtil.iterateAllResultSets(statement, true);
        try (ResultSet delivered = iterator.next()) {
            verify(result, never()).next();
            assertTrue(delivered.next());
        } finally {
            iterator.closeResource();
        }

        verify(result).next();
        verify(result).close();
        verify(statement, never()).getMoreResults();
        verify(statement, never()).getMoreResults(Statement.KEEP_CURRENT_RESULT);
    }

    @ParameterizedTest
    @ValueSource(strings = { "retrieval", "read", "advance" })
    void identificationFailureDoesNotHideResultErrors(final String operation) throws Exception {
        final Statement statement = statementWithIdentificationFailure("driverName");
        final ResultSet result = mock(ResultSet.class);
        final SQLException failure = new SQLException("Result operation failed", "HY000");
        when(statement.getResultSet()).thenReturn(result);

        final ObjIteratorEx<ResultSet> iterator = JdbcUtil.iterateAllResultSets(statement, true);
        try {
            if ("retrieval".equals(operation)) {
                // The next lookup lets iterator cleanup close the still-undelivered result after the failure.
                when(statement.getResultSet()).thenThrow(failure).thenReturn(result);
                assertSame(failure, assertThrows(UncheckedSQLException.class, iterator::next).getCause());
            } else {
                try (ResultSet delivered = iterator.next()) {
                    if ("read".equals(operation)) {
                        when(result.next()).thenThrow(failure);
                        assertSame(failure, assertThrows(SQLException.class, delivered::next));
                    } else {
                        when(statement.getMoreResults(Statement.KEEP_CURRENT_RESULT)).thenThrow(failure);
                        assertSame(failure, assertThrows(UncheckedSQLException.class, iterator::hasNext).getCause());
                        verify(result, never()).close();
                    }
                }
            }
        } finally {
            iterator.closeResource();
        }

        verify(result).close();
        verify(statement, never()).getMoreResults();
    }

    private static Statement statementWithIdentificationFailure(final String failurePoint) throws SQLException {
        final Statement statement = mock(Statement.class);
        final Connection connection = mock(Connection.class);
        final DatabaseMetaData metadata = mock(DatabaseMetaData.class);
        when(statement.getFetchSize()).thenReturn(Integer.MIN_VALUE);
        when(statement.getResultSetType()).thenReturn(ResultSet.TYPE_FORWARD_ONLY);
        when(statement.getResultSetConcurrency()).thenReturn(ResultSet.CONCUR_READ_ONLY);
        when(statement.getConnection()).thenReturn(connection);
        when(connection.getMetaData()).thenReturn(metadata);
        when(metadata.getDriverName()).thenReturn(com.mysql.cj.Constants.CJ_NAME);
        when(metadata.supportsMultipleOpenResults()).thenReturn(true);

        final SQLException failure = new SQLException("Driver identification unavailable", "HY000");
        switch (failurePoint) {
            case "connection" -> when(statement.getConnection()).thenThrow(failure);
            case "metadata" -> when(connection.getMetaData()).thenThrow(failure);
            case "driverName" -> when(metadata.getDriverName()).thenThrow(failure);
            default -> throw new IllegalArgumentException(failurePoint);
        }

        return statement;
    }

    private static void set(final StatementImpl target, final String name, final Object value) throws Exception {
        final Field field = StatementImpl.class.getDeclaredField(name);
        field.setAccessible(true);
        field.set(target, value);
    }
}
