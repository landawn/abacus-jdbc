package com.landawn.abacus.jdbc;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import com.landawn.abacus.exception.DuplicateResultException;
import com.landawn.abacus.type.Type;
import com.landawn.abacus.util.Dataset;
import com.landawn.abacus.util.Throwables;
import com.landawn.abacus.util.u.OptionalBoolean;
import com.landawn.abacus.util.u.OptionalByte;
import com.landawn.abacus.util.u.OptionalChar;
import com.landawn.abacus.util.u.OptionalDouble;
import com.landawn.abacus.util.u.OptionalFloat;
import com.landawn.abacus.util.u.OptionalInt;
import com.landawn.abacus.util.u.OptionalLong;
import com.landawn.abacus.util.u.OptionalShort;

class AbstractQueryResultTest extends AbstractQueryTestSupport {

    // queryForBoolean() — SQL NULL maps to a PRESENT OptionalBoolean.of(false), not empty().
    @Tag("2025")
    @Test
    public void testQueryForBoolean_SqlNull_PresentWithFalse() throws SQLException {
        final ResultSet rs = Mockito.mock(ResultSet.class);
        when(preparedStatement.executeQuery()).thenReturn(rs);
        when(rs.next()).thenReturn(true);
        when(rs.getBoolean(1)).thenReturn(false);

        assertEquals(OptionalBoolean.of(false), query.queryForBoolean());
    }

    // queryForChar() reads the column as a string and returns its first character, so a numeric 65
    // yields '6' rather than the code point 'A' (pins the documented getString-based conversion).
    @Test
    public void testQueryForChar_NumericColumn_ReturnsFirstCharacterOfStringForm() throws SQLException {
        final ResultSet rs = Mockito.mock(ResultSet.class);
        when(preparedStatement.executeQuery()).thenReturn(rs);
        when(rs.next()).thenReturn(true);
        when(rs.getInt(1)).thenReturn(65);
        when(rs.getString(1)).thenReturn("65");

        assertEquals(OptionalChar.of('6'), query.queryForChar());
    }

    // queryForChar() — no rows returns OptionalChar.empty() (line 4415)
    @Test
    public void testQueryForChar_NoResult_ReturnsEmpty() throws SQLException {
        final ResultSet rs = Mockito.mock(ResultSet.class);
        when(preparedStatement.executeQuery()).thenReturn(rs);
        when(rs.next()).thenReturn(false);
        // OptionalChar.empty() is returned; verify it is absent (isEmpty)
        assertTrue(query.queryForChar().isEmpty());
    }

    // queryForChar() — a row whose column is SQL NULL yields a PRESENT OptionalChar holding (char) 0;
    // empty() is reserved for the no-row case (documented present-with-default contract).
    @Tag("2025")
    @Test
    public void testQueryForChar_SqlNull_PresentWithDefault() throws SQLException {
        final ResultSet rs = Mockito.mock(ResultSet.class);
        when(preparedStatement.executeQuery()).thenReturn(rs);
        when(rs.next()).thenReturn(true);
        when(rs.getString(1)).thenReturn(null);

        assertEquals(OptionalChar.of((char) 0), query.queryForChar());
    }

    // queryForChar() — an empty string column also maps to a PRESENT (char) 0, per AbstractCharacterType.
    @Tag("2025")
    @Test
    public void testQueryForChar_EmptyString_PresentWithDefault() throws SQLException {
        final ResultSet rs = Mockito.mock(ResultSet.class);
        when(preparedStatement.executeQuery()).thenReturn(rs);
        when(rs.next()).thenReturn(true);
        when(rs.getString(1)).thenReturn("");

        assertEquals(OptionalChar.of((char) 0), query.queryForChar());
    }

    // queryForByte with result row → covers PC L4464 true branch
    @Test
    public void testQueryForByte_WithResult() throws SQLException {
        final ResultSet rs = Mockito.mock(ResultSet.class);
        when(preparedStatement.executeQuery()).thenReturn(rs);
        when(rs.next()).thenReturn(true);
        when(rs.getByte(1)).thenReturn((byte) 42);

        assertEquals(OptionalByte.of((byte) 42), query.queryForByte());
    }

    // queryForShort with result row → covers PC L4492 true branch
    @Test
    public void testQueryForShort_WithResult() throws SQLException {
        final ResultSet rs = Mockito.mock(ResultSet.class);
        when(preparedStatement.executeQuery()).thenReturn(rs);
        when(rs.next()).thenReturn(true);
        when(rs.getShort(1)).thenReturn((short) 999);

        assertEquals(OptionalShort.of((short) 999), query.queryForShort());
    }

    // queryForInt() — SQL NULL maps to a PRESENT OptionalInt.of(0) (ResultSet.getInt contract), not empty().
    @Tag("2025")
    @Test
    public void testQueryForInt_SqlNull_PresentWithZero() throws SQLException {
        final ResultSet rs = Mockito.mock(ResultSet.class);
        when(preparedStatement.executeQuery()).thenReturn(rs);
        when(rs.next()).thenReturn(true);
        when(rs.getInt(1)).thenReturn(0);

        assertEquals(OptionalInt.of(0), query.queryForInt());
    }

    // queryForLong with result row → covers PC L4551 true branch
    @Test
    public void testQueryForLong_WithResult() throws SQLException {
        final ResultSet rs = Mockito.mock(ResultSet.class);
        when(preparedStatement.executeQuery()).thenReturn(rs);
        when(rs.next()).thenReturn(true);
        when(rs.getLong(1)).thenReturn(888L);

        assertEquals(OptionalLong.of(888L), query.queryForLong());
    }

    // queryForFloat with result row → covers PC L4579 true branch
    @Test
    public void testQueryForFloat_WithResult() throws SQLException {
        final ResultSet rs = Mockito.mock(ResultSet.class);
        when(preparedStatement.executeQuery()).thenReturn(rs);
        when(rs.next()).thenReturn(true);
        when(rs.getFloat(1)).thenReturn(3.14f);

        assertEquals(OptionalFloat.of(3.14f), query.queryForFloat());
    }

    // queryForDouble with result row → covers PC L4607 true branch
    @Test
    public void testQueryForDouble_WithResult() throws SQLException {
        final ResultSet rs = Mockito.mock(ResultSet.class);
        when(preparedStatement.executeQuery()).thenReturn(rs);
        when(rs.next()).thenReturn(true);
        when(rs.getDouble(1)).thenReturn(2.718);

        assertEquals(OptionalDouble.of(2.718), query.queryForDouble());
    }

    @Test
    public void testQueryForBigIntegerPropagatesInvalidIntegerText() throws SQLException {
        final ResultSet rs = Mockito.mock(ResultSet.class);
        when(preparedStatement.executeQuery()).thenReturn(rs);
        when(rs.next()).thenReturn(true);
        when(rs.getString(1)).thenReturn("not-an-integer");

        assertThrows(NumberFormatException.class, query::queryForBigInteger);

        verify(rs).close();
        verify(preparedStatement).close();
    }

    // queryForUniqueValue(Type) - no rows returns Nullable.empty() (L4998)
    @Test
    public void testQueryForUniqueValue_Type_NoResult_ReturnsEmpty() throws SQLException {
        final ResultSet rs = Mockito.mock(ResultSet.class);
        when(preparedStatement.executeQuery()).thenReturn(rs);
        when(rs.next()).thenReturn(false);

        final com.landawn.abacus.util.u.Nullable<String> result = query.queryForUniqueValue(Type.of(String.class));

        assertFalse(result.isPresent());
    }

    @Test
    public void testQueryForUniqueNonNull_NullFirstValueWithSecondRowThrowsDuplicate() throws SQLException {
        final ResultSet rs = Mockito.mock(ResultSet.class);
        when(preparedStatement.executeQuery()).thenReturn(rs);
        when(rs.next()).thenReturn(true, true);
        when(rs.getString(1)).thenReturn(null);
        when(rs.getObject(1)).thenReturn("second");

        assertThrows(DuplicateResultException.class, () -> query.queryForUniqueNonNull(String.class));
    }

    // queryForUniqueNonNull(Type) - no rows returns Optional.empty() (L5074)
    @Test
    public void testQueryForUniqueNonNull_Type_NoResult_ReturnsEmpty() throws SQLException {
        final ResultSet rs = Mockito.mock(ResultSet.class);
        when(preparedStatement.executeQuery()).thenReturn(rs);
        when(rs.next()).thenReturn(false);

        final com.landawn.abacus.util.u.Optional<String> result = query.queryForUniqueNonNull(Type.of(String.class));

        assertFalse(result.isPresent());
    }

    @Test
    public void testQueryRejectsLiveResultSetReturnedByExtractor() throws SQLException {
        final ResultSet rs = Mockito.mock(ResultSet.class);
        when(preparedStatement.executeQuery()).thenReturn(rs);

        assertThrows(UnsupportedOperationException.class, () -> query.query(resultSet -> resultSet));

        verify(rs).close();
        verify(preparedStatement).close();
    }

    @Test
    public void testQueryRejectsLiveResultSetReturnedByBiExtractor() throws SQLException {
        final ResultSet rs = Mockito.mock(ResultSet.class);
        when(preparedStatement.executeQuery()).thenReturn(rs);
        when(rs.getMetaData()).thenReturn(Mockito.mock(ResultSetMetaData.class));

        assertThrows(UnsupportedOperationException.class, () -> query.query((resultSet, labels) -> resultSet));

        verify(rs).close();
        verify(preparedStatement).close();
    }

    @Test
    public void testQueryForUniqueResult_NullFirstValueWithSecondRowThrowsDuplicate() throws SQLException {
        final ResultSet rs = Mockito.mock(ResultSet.class);
        when(preparedStatement.executeQuery()).thenReturn(rs);
        when(rs.next()).thenReturn(true, true);
        when(rs.getString(1)).thenReturn(null);
        when(rs.getObject(1)).thenReturn("second");

        assertThrows(DuplicateResultException.class, () -> query.queryForUniqueValue(String.class));
    }

    // Closed-query state is checked before null functional-argument validation.
    @Test
    @Tag("2025")
    public void testQueryThenListThenHelpers_ClosedQueryWithNullArg_ThrowsIllegalState() {
        query.close();

        assertThrows(IllegalStateException.class, () -> query.queryThenApply(null));
        assertThrows(IllegalStateException.class, () -> query.queryThenApply(String.class, null));
        assertThrows(IllegalStateException.class, () -> query.queryThenAccept(null));
        assertThrows(IllegalStateException.class, () -> query.queryThenAccept(String.class, null));
        assertThrows(IllegalStateException.class, () -> query.listThenApply(String.class, null));
        assertThrows(IllegalStateException.class, () -> query.listThenApply((Jdbc.RowMapper<String>) rs -> "x", null));
        assertThrows(IllegalStateException.class, () -> query.listThenApply((Jdbc.BiRowMapper<String>) (rs, labels) -> "x", null));
        assertThrows(IllegalStateException.class, () -> query.listThenAccept(String.class, null));
        assertThrows(IllegalStateException.class, () -> query.listThenAccept((Jdbc.RowMapper<String>) rs -> "x", null));
        assertThrows(IllegalStateException.class, () -> query.listThenAccept((Jdbc.BiRowMapper<String>) (rs, labels) -> "x", null));
    }

    // The open-query behavior is unchanged: a null argument still fails fast with
    // IllegalArgumentException, and checkArgNotNull still closes the query before throwing.
    @Test
    @Tag("2025")
    public void testQueryByEntityClass_OpenQueryWithNullArg_StillThrowsIllegalArgumentAndClosesQuery() {
        assertThrows(IllegalArgumentException.class, () -> query.query((Class<?>) null));

        // checkArgNotNull closed the query, so the same call now reports the closed state.
        assertThrows(IllegalStateException.class, () -> query.query((Class<?>) null));
    }

    @Test
    public void testQuery2ResultSetsDrainsTrailingResultsWhenStatementIsReusable() throws SQLException {
        final ResultSet rs1 = Mockito.mock(ResultSet.class);
        final ResultSet rs2 = Mockito.mock(ResultSet.class);
        final ResultSet rs3 = Mockito.mock(ResultSet.class);
        final ResultSetMetaData metadata = Mockito.mock(ResultSetMetaData.class);
        when(rs1.getMetaData()).thenReturn(metadata);
        when(rs2.getMetaData()).thenReturn(metadata);
        when(metadata.getColumnCount()).thenReturn(0);
        when(preparedStatement.execute()).thenReturn(true);
        when(preparedStatement.getResultSet()).thenReturn(rs1, rs2, rs3);
        when(preparedStatement.getMoreResults(Statement.KEEP_CURRENT_RESULT)).thenReturn(true, true);
        when(preparedStatement.getMoreResults()).thenReturn(false);
        when(preparedStatement.getUpdateCount()).thenReturn(-1);
        query.closeAfterExecution(false);

        final com.landawn.abacus.util.Tuple.Tuple2<String, String> result = query.query2ResultSets((rs, labels) -> "first", (rs, labels) -> "second");

        assertEquals("first", result._1);
        assertEquals("second", result._2);
        verify(rs3).close();
        verify(preparedStatement).getMoreResults();
        verify(preparedStatement, never()).close();
    }

    @Test
    public void testQuery2ResultSetsPreservesExtractorFailureWhenDrainFails() throws SQLException {
        final ResultSet rs1 = Mockito.mock(ResultSet.class);
        final ResultSet rs2 = Mockito.mock(ResultSet.class);
        final ResultSetMetaData metadata = Mockito.mock(ResultSetMetaData.class);
        final SQLException extractorFailure = new SQLException("extractor failed");
        final SQLException drainFailure = new SQLException("drain failed");
        when(rs1.getMetaData()).thenReturn(metadata);
        when(metadata.getColumnCount()).thenReturn(0);
        when(preparedStatement.execute()).thenReturn(true);
        when(preparedStatement.getResultSet()).thenReturn(rs1, rs2);
        when(preparedStatement.getMoreResults(Statement.KEEP_CURRENT_RESULT)).thenReturn(true);
        when(preparedStatement.getMoreResults()).thenThrow(drainFailure);
        query.closeAfterExecution(false);

        final SQLException thrown = assertThrows(SQLException.class, () -> query.query2ResultSets((rs, labels) -> {
            throw extractorFailure;
        }, (rs, labels) -> "second"));

        assertSame(extractorFailure, thrown);
        assertArrayEquals(new Throwable[] { drainFailure }, thrown.getSuppressed());
        verify(rs2).close();
        verify(preparedStatement).getMoreResults();
        verify(preparedStatement, never()).close();
    }

    @Test
    public void testQuery3ResultSetsDrainsTrailingResultsWhenStatementIsReusable() throws SQLException {
        final ResultSet rs1 = Mockito.mock(ResultSet.class);
        final ResultSet rs2 = Mockito.mock(ResultSet.class);
        final ResultSet rs3 = Mockito.mock(ResultSet.class);
        final ResultSet rs4 = Mockito.mock(ResultSet.class);
        final ResultSetMetaData metadata = Mockito.mock(ResultSetMetaData.class);
        when(rs1.getMetaData()).thenReturn(metadata);
        when(rs2.getMetaData()).thenReturn(metadata);
        when(rs3.getMetaData()).thenReturn(metadata);
        when(metadata.getColumnCount()).thenReturn(0);
        when(preparedStatement.execute()).thenReturn(true);
        when(preparedStatement.getResultSet()).thenReturn(rs1, rs2, rs3, rs4);
        when(preparedStatement.getMoreResults(Statement.KEEP_CURRENT_RESULT)).thenReturn(true, true, true);
        when(preparedStatement.getMoreResults()).thenReturn(false);
        when(preparedStatement.getUpdateCount()).thenReturn(-1);
        query.closeAfterExecution(false);

        final com.landawn.abacus.util.Tuple.Tuple3<String, String, String> result = query.query3ResultSets((rs, labels) -> "first", (rs, labels) -> "second",
                (rs, labels) -> "third");

        assertEquals("first", result._1);
        assertEquals("second", result._2);
        assertEquals("third", result._3);
        verify(rs4).close();
        verify(preparedStatement).getMoreResults();
        verify(preparedStatement, never()).close();
    }

    @Test
    public void testQueryAllResultSetsDrainsAfterExtractorFailureWhenStatementIsReusable() throws SQLException {
        final ResultSet rs = Mockito.mock(ResultSet.class);
        final SQLException extractorFailure = new SQLException("extractor failed");
        when(preparedStatement.execute()).thenReturn(true);
        when(preparedStatement.getResultSet()).thenReturn(rs);
        when(preparedStatement.getMoreResults()).thenReturn(false);
        when(preparedStatement.getUpdateCount()).thenReturn(-1);
        query.closeAfterExecution(false);

        final SQLException thrown = assertThrows(SQLException.class, () -> query.queryAllResultSets(resultSet -> {
            throw extractorFailure;
        }));

        assertSame(extractorFailure, thrown);
        verify(preparedStatement).getMoreResults();
        verify(preparedStatement, never()).close();
    }

    @Test
    public void testQueryThenApplyRejectsNullFunctionBeforeQueryExecution() throws SQLException {
        final IllegalArgumentException iae = assertThrows(IllegalArgumentException.class,
                () -> query.queryThenApply((Throwables.Function<? super com.landawn.abacus.util.Dataset, Object, RuntimeException>) null));

        assertTrue(iae.getMessage().contains("func"));
        verify(preparedStatement, never()).executeQuery();
    }

    @Test
    public void testQueryThenApplyClassRejectsNullFunctionBeforeQueryExecution() throws SQLException {
        final IllegalArgumentException iae = assertThrows(IllegalArgumentException.class,
                () -> query.queryThenApply(QueryEntity.class, (Throwables.Function<? super com.landawn.abacus.util.Dataset, Object, RuntimeException>) null));

        assertTrue(iae.getMessage().contains("func"));
        verify(preparedStatement, never()).executeQuery();
    }

    @Test
    public void testQueryThenApplyChecksEntityTypeBeforeNullFunction() throws SQLException {
        final IllegalArgumentException failure = assertThrows(IllegalArgumentException.class,
                () -> query.queryThenApply(String.class, (Throwables.Function<? super com.landawn.abacus.util.Dataset, Object, RuntimeException>) null));

        assertTrue(failure.getMessage().contains("not a valid entity class"));
        verify(preparedStatement, never()).executeQuery();
        verify(preparedStatement, never()).close();
    }

    @Test
    public void testQueryThenAcceptRejectsNullConsumerBeforeQueryExecution() throws SQLException {
        final IllegalArgumentException iae = assertThrows(IllegalArgumentException.class,
                () -> query.queryThenAccept((Throwables.Consumer<? super com.landawn.abacus.util.Dataset, RuntimeException>) null));

        assertTrue(iae.getMessage().contains("consumer"));
        verify(preparedStatement, never()).executeQuery();
    }

    @Test
    public void testQueryThenAcceptClassRejectsNullConsumerBeforeQueryExecution() throws SQLException {
        final IllegalArgumentException iae = assertThrows(IllegalArgumentException.class,
                () -> query.queryThenAccept(QueryEntity.class, (Throwables.Consumer<? super com.landawn.abacus.util.Dataset, RuntimeException>) null));

        assertTrue(iae.getMessage().contains("consumer"));
        verify(preparedStatement, never()).executeQuery();
    }

    @Test
    public void testQueryThenAcceptChecksEntityTypeBeforeNullConsumer() throws SQLException {
        final IllegalArgumentException failure = assertThrows(IllegalArgumentException.class,
                () -> query.queryThenAccept(String.class, (Throwables.Consumer<? super com.landawn.abacus.util.Dataset, RuntimeException>) null));

        assertTrue(failure.getMessage().contains("not a valid entity class"));
        verify(preparedStatement, never()).executeQuery();
        verify(preparedStatement, never()).close();
    }

    @Test
    public void testFindOnlyOneOrNull_Class_NoResult_ReturnsNull() throws SQLException {
        final ResultSet rs = Mockito.mock(ResultSet.class);
        when(preparedStatement.executeQuery()).thenReturn(rs);
        when(rs.next()).thenReturn(false);

        assertNull(query.findOnlyOneOrNull(String.class));
    }

    @Test
    public void testFindOnlyOneOrNull_Class_DuplicateResultThrows() throws SQLException {
        final ResultSet rs = Mockito.mock(ResultSet.class);
        final java.sql.ResultSetMetaData meta = Mockito.mock(java.sql.ResultSetMetaData.class);
        when(preparedStatement.executeQuery()).thenReturn(rs);
        when(rs.next()).thenReturn(true, true);
        when(rs.getMetaData()).thenReturn(meta);
        when(meta.getColumnCount()).thenReturn(1);
        when(meta.getColumnLabel(1)).thenReturn("val");
        when(rs.getString(1)).thenReturn("first");

        assertThrows(DuplicateResultException.class, () -> query.findOnlyOneOrNull(String.class));
    }

    @Test
    public void testFindOnlyOneOrNull_RowMapper_NoResult_ReturnsNull() throws SQLException {
        final ResultSet rs = Mockito.mock(ResultSet.class);
        when(preparedStatement.executeQuery()).thenReturn(rs);
        when(rs.next()).thenReturn(false);

        assertNull(query.findOnlyOneOrNull(r -> r.getString(1)));
    }

    @Test
    public void testFindOnlyOneOrNull_RowMapper_DuplicateResultThrows() throws SQLException {
        final ResultSet rs = Mockito.mock(ResultSet.class);
        when(preparedStatement.executeQuery()).thenReturn(rs);
        when(rs.next()).thenReturn(true, true);
        when(rs.getString(1)).thenReturn("value");

        assertThrows(DuplicateResultException.class, () -> query.findOnlyOneOrNull(r -> r.getString(1)));
    }

    // Regression for exception-priority inversion in findOnlyOneOrNull: the duplicate-row check
    // must run BEFORE the null-mapped-value check, so that a query returning 2+ rows (whose first
    // row maps to null) throws DuplicateResultException, not NullPointerException. Mirrors the
    // established ordering of queryForUniqueNonNull / queryForUniqueValue.
    @Test
    @Tag("2025")
    public void testFindOnlyOneOrNull_RowMapper_NullMappingWithDuplicate_ThrowsDuplicateNotNpe() throws SQLException {
        final ResultSet rs = Mockito.mock(ResultSet.class);
        when(preparedStatement.executeQuery()).thenReturn(rs);
        when(rs.next()).thenReturn(true, true);

        assertThrows(DuplicateResultException.class, () -> query.findOnlyOneOrNull(r -> null));
    }

    @Test
    @Tag("2025")
    public void testFindOnlyOneOrNull_RowMapper_NullMappingSingleRow_ThrowsNpe() throws SQLException {
        final ResultSet rs = Mockito.mock(ResultSet.class);
        when(preparedStatement.executeQuery()).thenReturn(rs);
        when(rs.next()).thenReturn(true, false);

        // Exactly one row but the mapper yields null -> documented NullPointerException.
        assertThrows(NullPointerException.class, () -> query.findOnlyOneOrNull(r -> null));
    }

    @Test
    @Tag("2025")
    public void testFindOnlyOneOrNull_BiRowMapper_NullMappingWithDuplicate_ThrowsDuplicateNotNpe() throws SQLException {
        final ResultSet rs = Mockito.mock(ResultSet.class);
        final ResultSetMetaData meta = Mockito.mock(ResultSetMetaData.class);
        when(preparedStatement.executeQuery()).thenReturn(rs);
        when(rs.next()).thenReturn(true, true);
        when(rs.getMetaData()).thenReturn(meta);
        when(meta.getColumnCount()).thenReturn(1);
        when(meta.getColumnLabel(1)).thenReturn("val");

        assertThrows(DuplicateResultException.class, () -> query.findOnlyOneOrNull((r, labels) -> null));
    }

    @Test
    @Tag("2025")
    public void testFindOnlyOneOrNull_BiRowMapper_NullMappingSingleRow_ThrowsNpe() throws SQLException {
        final ResultSet rs = Mockito.mock(ResultSet.class);
        final ResultSetMetaData meta = Mockito.mock(ResultSetMetaData.class);
        when(preparedStatement.executeQuery()).thenReturn(rs);
        when(rs.next()).thenReturn(true, false);
        when(rs.getMetaData()).thenReturn(meta);
        when(meta.getColumnCount()).thenReturn(1);
        when(meta.getColumnLabel(1)).thenReturn("val");

        assertThrows(NullPointerException.class, () -> query.findOnlyOneOrNull((r, labels) -> null));
    }

    // findFirst(RowFilter, RowMapper) returns empty Optional when no row matches filter (L5962 / L6240)
    @Test
    @SuppressWarnings("deprecation")
    public void testFindFirst_RowFilter_RowMapper_NoMatchingRow() throws SQLException {
        final ResultSet rs = Mockito.mock(ResultSet.class);
        when(preparedStatement.executeQuery()).thenReturn(rs);
        when(rs.next()).thenReturn(true, false); // one row but filter rejects it

        final com.landawn.abacus.util.u.Optional<String> result = query.findFirst(r -> false, r -> r.getString(1));

        assertNotNull(result);
        assertFalse(result.isPresent());
    }

    // findFirst(BiRowFilter, BiRowMapper) no match returns empty Optional (L5962 + L6240)
    @Test
    public void testFindFirst_BiRowFilter_BiRowMapper_NoMatchingRow() throws SQLException {
        final ResultSet rs = Mockito.mock(ResultSet.class);
        final ResultSetMetaData meta = Mockito.mock(ResultSetMetaData.class);
        when(preparedStatement.executeQuery()).thenReturn(rs);
        when(rs.getMetaData()).thenReturn(meta);
        when(meta.getColumnCount()).thenReturn(0);
        when(rs.next()).thenReturn(true, false);

        com.landawn.abacus.util.u.Optional<String> result = query.findFirst((r, labels) -> false, (r, labels) -> "val");

        assertNotNull(result);
        assertFalse(result.isPresent());
    }

    // findFirstOrNull(Class) — no rows returns null (line 6079)
    @Test
    public void testFindFirstOrNull_Class_NoResult_ReturnsNull() throws SQLException {
        final ResultSet rs = Mockito.mock(ResultSet.class);
        when(preparedStatement.executeQuery()).thenReturn(rs);
        when(rs.next()).thenReturn(false);
        assertNull(query.findFirstOrNull(String.class));
    }

    // findFirstOrNull(RowMapper) — no rows returns null (line 6114)
    @Test
    public void testFindFirstOrNull_RowMapper_NoResult_ReturnsNull() throws SQLException {
        final ResultSet rs = Mockito.mock(ResultSet.class);
        when(preparedStatement.executeQuery()).thenReturn(rs);
        when(rs.next()).thenReturn(false);
        assertNull(query.findFirstOrNull(r -> r.getString(1)));
    }

    // findFirstOrNull(RowFilter, RowMapper) — no rows returns null (line 6157)
    @Test
    public void testFindFirstOrNull_RowFilter_RowMapper_NoResult() throws SQLException {
        final ResultSet rs = Mockito.mock(ResultSet.class);
        when(preparedStatement.executeQuery()).thenReturn(rs);
        when(rs.next()).thenReturn(false);
        assertNull(query.findFirstOrNull(r -> true, r -> r.getString(1)));
    }

    // list(RowFilter, RowMapper) — delegates and returns empty list (line 6442)
    @Test
    public void testList_RowFilter_RowMapper_EmptyResult() throws SQLException {
        final ResultSet rs = Mockito.mock(ResultSet.class);
        when(preparedStatement.executeQuery()).thenReturn(rs);
        when(rs.next()).thenReturn(false);
        List<String> result = query.list(r -> true, r -> r.getString(1));
        assertTrue(result.isEmpty());
    }

    // list(BiRowFilter, BiRowMapper) — delegates and returns empty list (line 6587)
    @Test
    public void testList_BiRowFilter_BiRowMapper_EmptyResult() throws SQLException {
        final ResultSet rs = Mockito.mock(ResultSet.class);
        final ResultSetMetaData meta = Mockito.mock(ResultSetMetaData.class);
        when(preparedStatement.executeQuery()).thenReturn(rs);
        when(rs.getMetaData()).thenReturn(meta);
        when(meta.getColumnCount()).thenReturn(0);
        when(rs.next()).thenReturn(false);
        List<String> result = query.list((r, labels) -> true, (r, labels) -> r.getString(1));
        assertTrue(result.isEmpty());
    }

    @Tag("2025")
    @DisplayName("list(null) should throw IllegalArgumentException mentioning targetType")
    @Test
    public void testList_NullTargetType_ThrowsIllegalArgumentException() {
        final IllegalArgumentException iae = assertThrows(IllegalArgumentException.class, () -> query.list((Class<?>) null));
        assertTrue(iae.getMessage().contains("targetType"));
    }

    @Tag("2025")
    @DisplayName("list(Class, int) with null targetType should throw IllegalArgumentException")
    @Test
    @SuppressWarnings("deprecation")
    public void testList_WithLimit_NullTargetType_ThrowsIllegalArgumentException() {
        final IllegalArgumentException iae = assertThrows(IllegalArgumentException.class, () -> query.list((Class<?>) null, 10));
        assertTrue(iae.getMessage().contains("targetType"));
    }

    @Test
    @SuppressWarnings("deprecation")
    public void testListRejectsNegativeLimitBeforeExecution() throws SQLException {
        final IllegalArgumentException failure = assertThrows(IllegalArgumentException.class, () -> query.list(String.class, -1));

        assertTrue(failure.getMessage().contains("maxResult"));
        verify(preparedStatement, never()).executeQuery();
        verify(preparedStatement).close();
    }

    @Test
    @Tag("2025")
    @SuppressWarnings("deprecation")
    public void testListWithMaxResult_ClosedQueryWithNullArg_ThrowsIllegalStateNotIllegalArgument() {
        query.close();

        assertThrows(IllegalStateException.class, () -> query.list((Class<String>) null, 10));
    }

    // listAllResultSets(Class) with at least one ResultSet → covers NC L6739 (while loop body)
    @Test
    public void testListAllResultSets_Class_WithResultSet() throws SQLException {
        final ResultSet rs = Mockito.mock(ResultSet.class);
        final ResultSetMetaData meta = Mockito.mock(ResultSetMetaData.class);
        when(preparedStatement.execute()).thenReturn(true);
        when(preparedStatement.getResultSet()).thenReturn(rs);
        when(rs.getMetaData()).thenReturn(meta);
        when(meta.getColumnCount()).thenReturn(0);
        when(rs.next()).thenReturn(false);
        when(preparedStatement.getUpdateCount()).thenReturn(-1);

        final List<List<String>> result = query.listAllResultSets(String.class);

        assertEquals(1, result.size());
        assertTrue(result.get(0).isEmpty());
    }

    // listAllResultSets(Class): no result sets → returns empty list (L6685-6709)
    @Test
    public void testListAllResultSets_Class_ReturnsEmptyList() throws SQLException {
        when(preparedStatement.execute()).thenReturn(false);
        when(preparedStatement.getUpdateCount()).thenReturn(-1);
        List<List<String>> result = query.listAllResultSets(String.class);
        assertTrue(result.isEmpty());
    }

    @Tag("2025")
    @DisplayName("listThenApply(null, func) should throw IllegalArgumentException mentioning targetType")
    @SuppressWarnings("unchecked")
    @Test
    public void testListThenApply_NullTargetType_ThrowsIllegalArgumentException() {
        final IllegalArgumentException iae = assertThrows(IllegalArgumentException.class,
                () -> query.listThenApply((Class<String>) null, (Throwables.Function<List<String>, String, RuntimeException>) list -> "ok"));
        assertTrue(iae.getMessage().contains("targetType"));
    }

    @Tag("2025")
    @DisplayName("listThenAccept(null, consumer) should throw IllegalArgumentException mentioning targetType")
    @SuppressWarnings("unchecked")
    @Test
    public void testListThenAccept_NullTargetType_ThrowsIllegalArgumentException() {
        final IllegalArgumentException iae = assertThrows(IllegalArgumentException.class,
                () -> query.listThenAccept((Class<String>) null, (Throwables.Consumer<List<String>, RuntimeException>) list -> {
                }));
        assertTrue(iae.getMessage().contains("targetType"));
    }

    public static final class QueryEntity {
        private String value;

        public String getValue() {
            return value;
        }

        public void setValue(final String value) {
            this.value = value;
        }
    }

    @Nested
    class ResultSetOwnership {
        static final class ResultTestQuery extends AbstractQuery<PreparedStatement, ResultTestQuery> {
            ResultTestQuery(final PreparedStatement stmt) {
                super(stmt);
            }
        }

        private PreparedStatement preparedStatement;
        private ResultTestQuery query;

        @BeforeEach
        public void setUp() throws SQLException {
            preparedStatement = Mockito.mock(PreparedStatement.class);
            final Connection connection = Mockito.mock(Connection.class);

            when(preparedStatement.getConnection()).thenReturn(connection);
            query = new ResultTestQuery(preparedStatement);
        }

        private ResultSet mockSingleEmptyResultSet() throws SQLException {
            final ResultSet rs = Mockito.mock(ResultSet.class);
            final ResultSetMetaData metadata = Mockito.mock(ResultSetMetaData.class);
            when(rs.getMetaData()).thenReturn(metadata);
            when(metadata.getColumnCount()).thenReturn(1);
            when(metadata.getColumnLabel(1)).thenReturn("value");
            when(metadata.getColumnName(1)).thenReturn("value");
            when(rs.next()).thenReturn(false);

            when(preparedStatement.execute()).thenReturn(true);
            when(preparedStatement.getResultSet()).thenReturn(rs);
            when(preparedStatement.getMoreResults(Statement.KEEP_CURRENT_RESULT)).thenReturn(false);
            when(preparedStatement.getUpdateCount()).thenReturn(-1);

            return rs;
        }

        // queryAllResultSets() closes each consumed result set quietly: a close failure is logged and
        // ignored rather than propagated (the Javadoc must not claim otherwise).
        @Test
        public void testQueryAllResultSetsIgnoresConsumedResultSetCloseFailure() throws SQLException {
            final ResultSet rs = mockSingleEmptyResultSet();
            doThrow(new SQLException("close failed")).when(rs).close();

            final List<Dataset> result = query.queryAllResultSets();

            assertEquals(1, result.size());
            assertEquals(0, result.get(0).size());
            verify(rs).close();
            verify(preparedStatement).close();
            assertTrue(query.isClosed);
        }

        // listAllResultSets(Class) has the same quiet-close contract as queryAllResultSets().
        @Test
        public void testListAllResultSetsClassIgnoresConsumedResultSetCloseFailure() throws SQLException {
            final ResultSet rs = mockSingleEmptyResultSet();
            doThrow(new SQLException("close failed")).when(rs).close();

            final List<List<String>> result = query.listAllResultSets(String.class);

            assertEquals(1, result.size());
            assertTrue(result.get(0).isEmpty());
            verify(rs).close();
            verify(preparedStatement).close();
            assertTrue(query.isClosed);
        }

        // When the statement is kept open, the trailing results are drained after the last consumed result
        // set; a drain failure is the only remaining failure and is propagated as the SQLException.
        @Test
        public void testQueryAllResultSetsPropagatesDrainFailureWhenStatementIsReusable() throws SQLException {
            final ResultSet rs = mockSingleEmptyResultSet();
            final SQLException drainFailure = new SQLException("drain failed");
            when(preparedStatement.getMoreResults()).thenThrow(drainFailure);
            query.closeAfterExecution(false);

            final SQLException thrown = assertThrows(SQLException.class, () -> query.queryAllResultSets());

            assertSame(drainFailure, thrown);
            verify(rs).close();
            verify(preparedStatement).getMoreResults();
            verify(preparedStatement, never()).close();
            assertFalse(query.isClosed);
        }

        // Without closeAfterExecution(false) nothing is drained: the statement is simply closed.
        @Test
        public void testListAllResultSetsClassDoesNotDrainWhenStatementIsClosedAfterExecution() throws SQLException {
            final ResultSet rs = mockSingleEmptyResultSet();

            final List<List<String>> result = query.listAllResultSets(String.class);

            assertEquals(1, result.size());
            verify(rs).close();
            verify(preparedStatement, never()).getMoreResults();
            verify(preparedStatement).close();
        }
    }
}
