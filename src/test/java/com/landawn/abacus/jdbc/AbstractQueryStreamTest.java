package com.landawn.abacus.jdbc;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.function.Consumer;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import com.landawn.abacus.exception.UncheckedSQLException;
import com.landawn.abacus.util.NoCachingNoUpdating.DisposableObjArray;
import com.landawn.abacus.util.stream.Stream;

class AbstractQueryStreamTest extends AbstractQueryTestSupport {

    // stream(RowFilter, RowMapper) creates stream pipeline (L7375-7384)
    @Test
    public void testStream_WithRowFilter_CreatesPipeline() throws SQLException {
        final ResultSet rs = Mockito.mock(ResultSet.class);
        when(preparedStatement.executeQuery()).thenReturn(rs);
        when(rs.next()).thenReturn(false);

        try (com.landawn.abacus.util.stream.Stream<String> s = query.stream((r) -> true, r -> r.getString(1))) {
            assertNotNull(s);
        }
    }

    // stream(BiRowFilter, BiRowMapper) creates stream pipeline (L7425-7434)
    @Test
    public void testStream_WithBiRowFilter_CreatesPipeline() throws SQLException {
        final ResultSet rs = Mockito.mock(ResultSet.class);
        final ResultSetMetaData meta = Mockito.mock(ResultSetMetaData.class);
        when(preparedStatement.executeQuery()).thenReturn(rs);
        when(rs.getMetaData()).thenReturn(meta);
        when(meta.getColumnCount()).thenReturn(0);
        when(rs.next()).thenReturn(false);

        try (com.landawn.abacus.util.stream.Stream<String> s = query.stream((r, labels) -> true, (r, labels) -> "val")) {
            assertNotNull(s);
        }
    }

    // Consuming stream(RowFilter, RowMapper) forces the flatMap lambda (L7686) to run:
    // map(Supplier::get) executes the query, flatMap builds JdbcUtil.stream(rs, filter, mapper).
    @Test
    public void testStream_WithRowFilter_Consumed() throws SQLException {
        final ResultSet rs = Mockito.mock(ResultSet.class);
        when(preparedStatement.executeQuery()).thenReturn(rs);
        when(rs.next()).thenReturn(false);

        try (Stream<String> s = query.stream((r) -> true, r -> r.getString(1))) {
            final List<String> result = s.toList();
            assertEquals(0, result.size());
        }
    }

    // Consuming stream(BiRowFilter, BiRowMapper) forces the flatMap lambda (L7734) to run;
    // the BiRowFilter iterate path reads column labels first, so metadata must be stubbed.
    @Test
    public void testStream_WithBiRowFilter_Consumed() throws SQLException {
        final ResultSet rs = Mockito.mock(ResultSet.class);
        final ResultSetMetaData meta = Mockito.mock(ResultSetMetaData.class);
        when(preparedStatement.executeQuery()).thenReturn(rs);
        when(rs.getMetaData()).thenReturn(meta);
        when(meta.getColumnCount()).thenReturn(0);
        when(rs.next()).thenReturn(false);

        try (Stream<String> s = query.stream((r, labels) -> true, (r, labels) -> "val")) {
            final List<String> result = s.toList();
            assertEquals(0, result.size());
        }
    }

    @Tag("2025")
    @DisplayName("stream(null) should throw IllegalArgumentException mentioning targetType")
    @Test
    public void testStream_NullTargetType_ThrowsIllegalArgumentException() {
        final IllegalArgumentException iae = assertThrows(IllegalArgumentException.class, () -> query.stream((Class<?>) null));
        assertTrue(iae.getMessage().contains("targetType"));
    }

    // createQuerySupplier lambda: executeQuery() throws SQLException → wrapped in UncheckedSQLException (L7706-7707)
    @Test
    public void testStream_RowMapper_ExecuteQueryThrows_WrapsUncheckedSQLException() throws SQLException {
        when(preparedStatement.executeQuery()).thenThrow(new SQLException("forced"));
        Stream<String> stream = query.stream(rs -> rs.getString(1));
        assertThrows(UncheckedSQLException.class, stream::toList);
        verify(preparedStatement).close();
    }

    // streamAllResultSets() – no-arg delegates to ResultExtractor.TO_DATASET (L7601)
    @Test
    public void testStreamAllResultSets_NoArg() throws SQLException {
        when(preparedStatement.execute()).thenReturn(false);
        when(preparedStatement.getUpdateCount()).thenReturn(-1);

        try (com.landawn.abacus.util.stream.Stream<?> s = query.streamAllResultSets()) {
            assertNotNull(s);
        }
    }

    // streamAllResultSets(ResultExtractor) creates stream pipeline (L7638-7646)
    @Test
    public void testStreamAllResultSets_WithResultExtractor() throws SQLException {
        when(preparedStatement.execute()).thenReturn(false);
        when(preparedStatement.getUpdateCount()).thenReturn(-1);

        try (com.landawn.abacus.util.stream.Stream<String> s = query.streamAllResultSets(Jdbc.ResultExtractor.TO_DATASET.andThen(ds -> "result"))) {
            assertNotNull(s);
        }
    }

    // streamAllResultSets(BiResultExtractor) creates stream pipeline (L7691-7699)
    @Test
    public void testStreamAllResultSets_WithBiResultExtractor() throws SQLException {
        when(preparedStatement.execute()).thenReturn(false);
        when(preparedStatement.getUpdateCount()).thenReturn(-1);

        try (com.landawn.abacus.util.stream.Stream<String> s = query.streamAllResultSets(Jdbc.BiResultExtractor.TO_DATASET.andThen(ds -> "result"))) {
            assertNotNull(s);
        }
    }

    // Consume streamAllResultSets to trigger createExecuteSupplier -> JdbcUtil.execute(stmt) (L7759)
    @Test
    public void testStreamAllResultSets_ConsumeStream_HitsExecuteSupplier() throws SQLException {
        when(preparedStatement.execute()).thenReturn(false);
        when(preparedStatement.getUpdateCount()).thenReturn(-1);

        try (com.landawn.abacus.util.stream.Stream<com.landawn.abacus.util.Dataset> s = query.streamAllResultSets()) {
            assertNotNull(s);
            final java.util.List<com.landawn.abacus.util.Dataset> results = s.toList();
            assertEquals(0, results.size());
        }
    }

    @Test
    public void testStreamAllResultSetsClosingBeforeTraversalLeavesReusableStatementUntouched() throws SQLException {
        query.closeAfterExecution(false);
        when(preparedStatement.getMoreResults()).thenThrow(new SQLException("Statement has not been executed"));

        try (Stream<String> results = query.streamAllResultSets((Jdbc.ResultExtractor<String>) rs -> "value")) {
            assertNotNull(results);
        }
        try (Stream<String> results = query.streamAllResultSets((Jdbc.BiResultExtractor<String>) (rs, labels) -> "value")) {
            assertNotNull(results);
        }

        verify(preparedStatement, never()).execute();
        verify(preparedStatement, never()).getMoreResults();
        verify(preparedStatement, never()).getUpdateCount();
        verify(preparedStatement, never()).close();
        assertFalse(query.isClosed);
    }

    // Fully consuming streamAllResultSets(BiResultExtractor) over two result sets drives the
    // iterateAllResultSets flatMap + mapE + onClose path (L7874-7884). The BiResultExtractor
    // receives column labels via getColumnLabels(rs), so each ResultSet needs metadata.
    @Test
    public void testStreamAllResultSets_WithBiResultExtractor_MultipleResultSets() throws SQLException {
        final ResultSet first = Mockito.mock(ResultSet.class);
        final ResultSet second = Mockito.mock(ResultSet.class);
        final ResultSetMetaData meta = Mockito.mock(ResultSetMetaData.class);

        when(preparedStatement.execute()).thenReturn(true);
        when(preparedStatement.getResultSet()).thenReturn(first, second);
        when(preparedStatement.getMoreResults(Statement.KEEP_CURRENT_RESULT)).thenReturn(true, false);
        // -1 update count signals "no more results" so the fully-drained iterator terminates.
        when(preparedStatement.getUpdateCount()).thenReturn(-1);
        when(first.getMetaData()).thenReturn(meta);
        when(second.getMetaData()).thenReturn(meta);
        when(meta.getColumnCount()).thenReturn(0);

        try (Stream<String> stream = query.streamAllResultSets((Jdbc.BiResultExtractor<String>) (rs, labels) -> "value")) {
            final List<String> result = stream.toList();
            assertEquals(List.of("value", "value"), result);
        }

        verify(first).close();
        verify(second).close();
    }

    @Test
    public void testStreamAllResultSetsRejectsLiveResultSetDuringConsumption() throws SQLException {
        final ResultSet rs = Mockito.mock(ResultSet.class);
        when(preparedStatement.execute()).thenReturn(true);
        when(preparedStatement.getResultSet()).thenReturn(rs);
        when(preparedStatement.getUpdateCount()).thenReturn(-1);
        when(rs.getMetaData()).thenReturn(Mockito.mock(ResultSetMetaData.class));

        try (Stream<ResultSet> results = query.streamAllResultSets((resultSet, labels) -> resultSet)) {
            verify(preparedStatement, never()).execute();
            assertThrows(UnsupportedOperationException.class, results::toList);
        }

        verify(rs, Mockito.atLeastOnce()).close();
        verify(preparedStatement).close();
    }

    // createExecuteSupplier lambda: stmt.execute() throws SQLException → wrapped in UncheckedSQLException (L7715-7717)
    @Test
    public void testStreamAllResultSets_ResultExtractor_ExecuteThrows_WrapsUncheckedSQLException() throws SQLException {
        when(preparedStatement.execute()).thenThrow(new SQLException("forced"));
        Stream<List<String>> stream = query.streamAllResultSets((Jdbc.ResultExtractor<List<String>>) rs -> new ArrayList<>());
        assertThrows(UncheckedSQLException.class, stream::toList);
        verify(preparedStatement).close();
    }

    @Test
    public void testStreamAllResultSets_CloseClosesBufferedResultSetAndDrainsReusableStatement() throws SQLException {
        final ResultSet first = Mockito.mock(ResultSet.class);
        final ResultSet second = Mockito.mock(ResultSet.class);

        when(preparedStatement.execute()).thenReturn(true);
        when(preparedStatement.getResultSet()).thenReturn(first, second);
        when(preparedStatement.getMoreResults(Statement.KEEP_CURRENT_RESULT)).thenReturn(true, false);
        when(preparedStatement.getMoreResults()).thenReturn(false);
        when(preparedStatement.getUpdateCount()).thenReturn(-1);
        query.closeAfterExecution(false);

        try (Stream<String> stream = query.streamAllResultSets((Jdbc.ResultExtractor<String>) rs -> "value")) {
            final java.util.Iterator<String> iter = stream.iterator();
            assertTrue(iter.hasNext());
            assertEquals("value", iter.next());
            assertTrue(iter.hasNext());
        }

        verify(first).close();
        verify(second).close();
        verify(preparedStatement).getMoreResults();
        verify(preparedStatement, never()).close();
    }

    // Regression: with closeAfterExecution(false), closing a partially consumed streamAllResultSets stream after
    // the caller had already closed the query still drained the (closed) statement, so Stream.close() threw the
    // driver's "statement is closed" SQLException wrapped in UncheckedSQLException.
    @Test
    public void testStreamAllResultSetsClosedAfterQueryCloseDoesNotDrainClosedStatement() throws SQLException {
        for (final boolean withColumnLabels : new boolean[] { false, true }) {
            final PreparedStatement statement = Mockito.mock(PreparedStatement.class);
            final ResultSet rs = Mockito.mock(ResultSet.class);
            final TestQuery streamQuery = new TestQuery(statement).closeAfterExecution(false);
            when(statement.execute()).thenReturn(true);
            when(statement.getResultSet()).thenReturn(rs);
            when(statement.getMoreResults(Statement.KEEP_CURRENT_RESULT)).thenReturn(true);
            when(statement.getMoreResults()).thenThrow(new SQLException("Statement is closed"));
            when(rs.getMetaData()).thenReturn(Mockito.mock(ResultSetMetaData.class));

            final Stream<String> results = withColumnLabels ? streamQuery.streamAllResultSets((Jdbc.BiResultExtractor<String>) (result, labels) -> "value")
                    : streamQuery.streamAllResultSets((Jdbc.ResultExtractor<String>) result -> "value");
            final java.util.Iterator<String> iter = results.iterator();
            assertEquals("value", iter.next());

            streamQuery.close();
            assertDoesNotThrow(results::close);

            verify(statement, never()).getMoreResults();
            verify(statement).close();
        }
    }

    @Test
    public void testStreamAllResultSetsClosingBeforeTraversalStillClosesOwnedStatement() throws SQLException {
        for (final boolean withColumnLabels : new boolean[] { false, true }) {
            final PreparedStatement statement = Mockito.mock(PreparedStatement.class);
            final TestQuery ownedQuery = new TestQuery(statement);

            try (Stream<String> results = withColumnLabels ? ownedQuery.streamAllResultSets((Jdbc.BiResultExtractor<String>) (rs, labels) -> "value")
                    : ownedQuery.streamAllResultSets((Jdbc.ResultExtractor<String>) rs -> "value")) {
                assertNotNull(results);
            }

            verify(statement, never()).execute();
            verify(statement, never()).getMoreResults();
            verify(statement).close();
            assertTrue(ownedQuery.isClosed);
        }
    }

    @Test
    public void testStreamAllResultSetsFailedExecutionDoesNotDrainReusableStatementOnClose() throws SQLException {
        final SQLException executionFailure = new SQLException("execution failed");
        when(preparedStatement.execute()).thenThrow(executionFailure);
        when(preparedStatement.getMoreResults()).thenThrow(new SQLException("Statement has not been executed"));
        query.closeAfterExecution(false);

        try (Stream<String> results = query.streamAllResultSets((Jdbc.ResultExtractor<String>) rs -> "value")) {
            final UncheckedSQLException thrown = assertThrows(UncheckedSQLException.class, results::toList);
            assertSame(executionFailure, thrown.getCause());
        }
        try (Stream<String> results = query.streamAllResultSets((Jdbc.BiResultExtractor<String>) (rs, labels) -> "value")) {
            final UncheckedSQLException thrown = assertThrows(UncheckedSQLException.class, results::toList);
            assertSame(executionFailure, thrown.getCause());
        }

        verify(preparedStatement, never()).getMoreResults();
        verify(preparedStatement, never()).close();
    }

    // foreach(Class, Consumer<DisposableObjArray>) delegates to forEach(RowConsumer) (L8686-8689)
    @Test
    public void testForeach_WithEntityClassAndConsumer() throws SQLException {
        final ResultSet rs = Mockito.mock(ResultSet.class);
        final ResultSetMetaData meta = Mockito.mock(ResultSetMetaData.class);
        when(preparedStatement.executeQuery()).thenReturn(rs);
        when(rs.getMetaData()).thenReturn(meta);
        when(meta.getColumnCount()).thenReturn(0);
        when(rs.next()).thenReturn(false);

        // No rows, so the consumer must never be invoked and the delegation must not throw.
        final boolean[] consumed = { false };
        assertDoesNotThrow(() -> query.foreach(String.class, arr -> consumed[0] = true));
        assertFalse(consumed[0]);
    }

    // forEach(RowFilter, RowConsumer): rowFilter returns true → rowConsumer.accept called (L8463)
    @Test
    public void testForEach_WithRowFilter_MatchingRow_CallsConsumer() throws SQLException {
        ResultSet rs = Mockito.mock(ResultSet.class);
        when(preparedStatement.executeQuery()).thenReturn(rs);
        when(rs.next()).thenReturn(true, false);

        List<ResultSet> consumed = new ArrayList<>();
        query.forEach(r -> true, consumed::add);
        assertEquals(1, consumed.size());
    }

    // forEach(RowFilter, RowConsumer) – filter rejects row so consumer not called (L8455-8469)
    @Test
    public void testForEach_WithRowFilter_FilterRejectsRow() throws SQLException {
        final ResultSet rs = Mockito.mock(ResultSet.class);
        when(preparedStatement.executeQuery()).thenReturn(rs);
        when(rs.next()).thenReturn(true, false);

        final boolean[] consumed = { false };
        query.forEach((r) -> false, r -> consumed[0] = true);

        assertFalse(consumed[0]);
    }

    // foreach(Class, Consumer<DisposableObjArray>) — null entityClass must throw IllegalArgumentException (bug fix)
    @Test
    public void testForeach_NullEntityClass_ThrowsIllegalArgumentException() {
        assertThrows(IllegalArgumentException.class, () -> query.foreach(null, arr -> {
        }));
    }
}
