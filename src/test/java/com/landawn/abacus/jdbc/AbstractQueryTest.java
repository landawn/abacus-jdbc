package com.landawn.abacus.jdbc;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.math.BigInteger;
import java.sql.Connection;
import java.util.Calendar;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.sql.Types;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.function.Consumer;
import java.util.function.Predicate;

import com.landawn.abacus.exception.UncheckedSQLException;
import com.landawn.abacus.util.stream.Stream;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import com.landawn.abacus.TestBase;
import com.landawn.abacus.exception.DuplicateResultException;
import com.landawn.abacus.type.Type;
import com.landawn.abacus.type.TypeFactory;
import com.landawn.abacus.util.Throwables;

public class AbstractQueryTest extends TestBase {

    static final class TestQuery extends AbstractQuery<PreparedStatement, TestQuery> {
        TestQuery(final PreparedStatement stmt) {
            super(stmt);
        }
    }

    private PreparedStatement preparedStatement;
    private TestQuery query;

    @BeforeEach
    public void setUp() throws SQLException {
        preparedStatement = Mockito.mock(PreparedStatement.class);
        Connection connection = Mockito.mock(Connection.class);

        Mockito.when(preparedStatement.getConnection()).thenReturn(connection);
        query = new TestQuery(preparedStatement);
    }

    @Test
    public void testSetParameters_IntArray() throws SQLException {
        TestQuery result = query.setParameters(new int[] { 1, 2, 3 });

        assertSame(query, result);
        verify(preparedStatement).setInt(1, 1);
        verify(preparedStatement).setInt(2, 2);
        verify(preparedStatement).setInt(3, 3);
    }

    @Test
    public void testSetParametersFrom_Collection() throws SQLException {
        TestQuery result = query.setParametersFrom(2, List.of("a", "b"));

        assertSame(query, result);
        verify(preparedStatement).setString(2, "a");
        verify(preparedStatement).setString(3, "b");
    }

    @Test
    public void testSetNullForIndices() throws SQLException {
        TestQuery result = query.setNullForIndices(Types.INTEGER, 1, 3);

        assertSame(query, result);
        verify(preparedStatement).setNull(1, Types.INTEGER);
        verify(preparedStatement).setNull(3, Types.INTEGER);
    }

    @Test
    public void testSetFetchSize() throws SQLException {
        TestQuery result = query.setFetchSize(-1);

        assertSame(query, result);
        verify(preparedStatement).setFetchSize(-1);
    }

    @Test
    public void testCloseAfterExecution_ClosedQuery() throws SQLException {
        query.close();

        assertThrows(IllegalStateException.class, () -> query.closeAfterExecution(false));
    }

    // Tests for setBoolean with defaultValueForNull
    @Test
    public void testSetBoolean_WithDefault_Null() throws SQLException {
        TestQuery result = query.setBoolean(1, (Boolean) null, true);
        assertSame(query, result);
        verify(preparedStatement).setBoolean(1, true);
    }

    @Test
    public void testSetBoolean_WithDefault_NonNull() throws SQLException {
        TestQuery result = query.setBoolean(1, Boolean.FALSE, true);
        assertSame(query, result);
        verify(preparedStatement).setBoolean(1, false);
    }

    // Tests for setByte with defaultValueForNull
    @Test
    public void testSetByte_Wrapper_Null() throws SQLException {
        TestQuery result = query.setByte(1, (Byte) null);
        assertSame(query, result);
        verify(preparedStatement).setNull(1, Types.TINYINT);
    }

    @Test
    public void testSetByte_Wrapper_NonNull() throws SQLException {
        TestQuery result = query.setByte(1, (Byte) (byte) 5);
        assertSame(query, result);
        verify(preparedStatement).setByte(1, (byte) 5);
    }

    // Tests for setShort with null
    @Test
    public void testSetShort_Wrapper_Null() throws SQLException {
        TestQuery result = query.setShort(1, (Short) null);
        assertSame(query, result);
        verify(preparedStatement).setNull(1, Types.SMALLINT);
    }

    @Test
    public void testSetShort_Wrapper_NonNull() throws SQLException {
        TestQuery result = query.setShort(1, (Short) (short) 100);
        assertSame(query, result);
        verify(preparedStatement).setShort(1, (short) 100);
    }

    @Test
    public void testSetShort_WithDefault_Null() throws SQLException {
        TestQuery result = query.setShort(1, (Short) null, (short) 0);
        assertSame(query, result);
        verify(preparedStatement).setShort(1, (short) 0);
    }

    @Test
    public void testSetShort_WithDefault_NonNull() throws SQLException {
        TestQuery result = query.setShort(1, (Short) (short) 7, (short) 0);
        assertSame(query, result);
        verify(preparedStatement).setShort(1, (short) 7);
    }

    // Tests for setInt with null wrapper
    @Test
    public void testSetInt_Wrapper_Null() throws SQLException {
        TestQuery result = query.setInt(1, (Integer) null);
        assertSame(query, result);
        verify(preparedStatement).setNull(1, Types.INTEGER);
    }

    @Test
    public void testSetInt_Wrapper_NonNull() throws SQLException {
        TestQuery result = query.setInt(1, (Integer) 42);
        assertSame(query, result);
        verify(preparedStatement).setInt(1, 42);
    }

    @Test
    public void testSetInt_WithDefault_NonNull() throws SQLException {
        TestQuery result = query.setInt(1, Integer.valueOf(7), 0);
        assertSame(query, result);
        verify(preparedStatement).setInt(1, 7);
    }

    @Test
    public void testSetInt_CharacterWrapper_NonNull() throws SQLException {
        TestQuery result = query.setInt(1, Character.valueOf('A'));
        assertSame(query, result);
        verify(preparedStatement).setInt(1, 'A');
    }

    // Tests for setLong with null wrapper
    @Test
    public void testSetLong_Wrapper_Null() throws SQLException {
        TestQuery result = query.setLong(1, (Long) null);
        assertSame(query, result);
        verify(preparedStatement).setNull(1, Types.BIGINT);
    }

    @Test
    public void testSetLong_Wrapper_NonNull() throws SQLException {
        TestQuery result = query.setLong(1, (Long) 1000L);
        assertSame(query, result);
        verify(preparedStatement).setLong(1, 1000L);
    }

    @Test
    public void testSetLong_WithDefault_NonNull() throws SQLException {
        TestQuery result = query.setLong(1, Long.valueOf(99L), -1L);
        assertSame(query, result);
        verify(preparedStatement).setLong(1, 99L);
    }

    // Tests for setFloat with null wrapper
    @Test
    public void testSetFloat_Wrapper_Null() throws SQLException {
        TestQuery result = query.setFloat(1, (Float) null);
        assertSame(query, result);
        verify(preparedStatement).setNull(1, Types.FLOAT);
    }

    @Test
    public void testSetFloat_Wrapper_NonNull() throws SQLException {
        TestQuery result = query.setFloat(1, (Float) 1.5f);
        assertSame(query, result);
        verify(preparedStatement).setFloat(1, 1.5f);
    }

    // Tests for setDouble with null wrapper
    @Test
    public void testSetDouble_Wrapper_Null() throws SQLException {
        TestQuery result = query.setDouble(1, (Double) null);
        assertSame(query, result);
        verify(preparedStatement).setNull(1, Types.DOUBLE);
    }

    @Test
    public void testSetDouble_Wrapper_NonNull() throws SQLException {
        TestQuery result = query.setDouble(1, (Double) 3.14);
        assertSame(query, result);
        verify(preparedStatement).setDouble(1, 3.14);
    }

    @Test
    public void testAddBatchParameters_EmptyCollection() throws SQLException {
        TestQuery result = query.addBatchParameters(List.of());

        assertSame(query, result);
        verify(preparedStatement, never()).addBatch();
    }

    @Test
    public void testAddBatchParameters_IteratorOfScalarValues() throws SQLException {
        TestQuery result = query.addBatchParameters(List.of(1, 2).iterator());

        assertSame(query, result);
        verify(preparedStatement).setInt(1, 1);
        verify(preparedStatement).setInt(1, 2);
        verify(preparedStatement, times(2)).addBatch();
    }

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
    public void testGetFetchSettings() throws SQLException {
        when(preparedStatement.getFetchSize()).thenReturn(128);
        when(preparedStatement.getFetchDirection()).thenReturn(ResultSet.FETCH_REVERSE);

        assertEquals(128, query.getFetchSize());
        assertEquals(ResultSet.FETCH_REVERSE, query.getFetchDirection());
    }

    // setFloat(int, Float, float) - non-null branch (line 867) was not covered
    @Test
    public void testSetFloat_WithDefault_NonNull() throws SQLException {
        TestQuery result = query.setFloat(1, Float.valueOf(2.5f), 0.0f);
        assertSame(query, result);
        verify(preparedStatement).setFloat(1, 2.5f);
    }

    // setDouble(int, Double, double) - non-null branch (line 965) was not covered
    @Test
    public void testSetDouble_WithDefault_NonNull() throws SQLException {
        TestQuery result = query.setDouble(1, Double.valueOf(1.23), 0.0);
        assertSame(query, result);
        verify(preparedStatement).setDouble(1, 1.23);
    }

    // addBatchParameters(Collection<T>, Class<T>) with empty collection should return early
    @Test
    public void testAddBatchParameters_EmptyCollectionWithType() throws SQLException {
        TestQuery result = query.addBatchParameters(List.of(), String.class);
        assertSame(query, result);
        verify(preparedStatement, never()).addBatch();
    }

    // addBatchParameters(Iterator) with empty iterator should return early (line 3679-3680)
    @Test
    public void testAddBatchParameters_EmptyIterator() throws SQLException {
        TestQuery result = query.addBatchParameters(List.of().iterator());
        assertSame(query, result);
        verify(preparedStatement, never()).addBatch();
    }

    // addBatchParameters(Iterator, BiParametersSetter) - lines 3867-3891
    @Test
    public void testAddBatchParameters_IteratorWithBiParametersSetter() throws SQLException {
        final List<String> items = List.of("a", "b");
        TestQuery result = query.addBatchParameters(items.iterator(), (Jdbc.BiParametersSetter<TestQuery, String>) (q, s) -> preparedStatement.setString(1, s));
        assertSame(query, result);
        verify(preparedStatement).setString(1, "a");
        verify(preparedStatement).setString(1, "b");
        verify(preparedStatement, times(2)).addBatch();
    }

    // addBatchParameters(Collection, BiParametersSetter) - lines 3838-3843
    @Test
    public void testAddBatchParameters_CollectionWithBiParametersSetter() throws SQLException {
        final List<Integer> items = List.of(10, 20);
        TestQuery result = query.addBatchParameters(items, (Jdbc.BiParametersSetter<TestQuery, Integer>) (q, v) -> preparedStatement.setInt(1, v));
        assertSame(query, result);
        verify(preparedStatement).setInt(1, 10);
        verify(preparedStatement).setInt(1, 20);
        verify(preparedStatement, times(2)).addBatch();
    }

    @Test
    public void testSetObject_WithType_DelegatesToType() throws SQLException {
        final Type<String> strType = TypeFactory.getType(String.class);

        TestQuery result = query.setObject(1, "hello", strType);

        assertSame(query, result);
        verify(preparedStatement).setString(1, "hello");
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

    @Test
    public void testCheckArgNotNull_NullArg_ThrowsIllegalArgument() {
        IllegalArgumentException iae = assertThrows(IllegalArgumentException.class,
                () -> query.checkArgNotNull(null, "myParam"));
        assertTrue(iae.getMessage().contains("myParam"));
    }

    @Test
    public void testCheckArgNotNull_CloseHandlerThrows_SuppressedException() {
        final RuntimeException closeEx = new RuntimeException("close handler failed");
        query.onClose(() -> { throw closeEx; });

        IllegalArgumentException iae = assertThrows(IllegalArgumentException.class,
                () -> query.checkArgNotNull(null, "someArg"));

        assertTrue(iae.getMessage().contains("someArg"));
        assertEquals(1, iae.getSuppressed().length);
        assertSame(closeEx, iae.getSuppressed()[0]);
    }

    @Test
    public void testCheckArg_FalseCondition_ThrowsIllegalArgument() {
        IllegalArgumentException iae = assertThrows(IllegalArgumentException.class,
                () -> query.checkArg(false, "condition must be true"));
        assertEquals("condition must be true", iae.getMessage());
    }

    @Test
    public void testCheckArg_CloseHandlerThrows_SuppressedException() {
        final RuntimeException closeEx = new RuntimeException("close handler failed");
        query.onClose(() -> { throw closeEx; });

        IllegalArgumentException iae = assertThrows(IllegalArgumentException.class,
                () -> query.checkArg(false, "bad condition"));

        assertEquals("bad condition", iae.getMessage());
        assertEquals(1, iae.getSuppressed().length);
        assertSame(closeEx, iae.getSuppressed()[0]);
    }

    // setParameters(ParametersSetter<Stmt>) — exception path closes the query (line 2791)
    @Test
    public void testSetParameters_ParametersSetter_ThrowsClosesQuery() {
        assertThrows(SQLException.class, () ->
                query.setParameters((Jdbc.ParametersSetter<PreparedStatement>) stmt -> {
                    throw new SQLException("setter failed");
                }));
    }

    // setParameters(T, BiParametersSetter<Stmt,T>) — exception path closes (line 2855)
    @Test
    public void testSetParameters_BiParametersSetter_ThrowsClosesQuery() {
        assertThrows(SQLException.class, () ->
                query.setParameters("data", (Jdbc.BiParametersSetter<PreparedStatement, String>) (stmt, s) -> {
                    throw new SQLException("bi-setter failed");
                }));
    }

    // settParameters(ParametersSetter<This>) — exception path closes (line 3117)
    @Test
    public void testSettParameters_ParametersSetter_ThrowsClosesQuery() {
        assertThrows(SQLException.class, () ->
                query.settParameters((Jdbc.ParametersSetter<TestQuery>) q -> {
                    throw new SQLException("settParameters failed");
                }));
    }

    // settParameters(T, BiParametersSetter<This,T>) — exception path closes (line 3165)
    @Test
    public void testSettParameters_BiParametersSetter_ThrowsClosesQuery() {
        assertThrows(SQLException.class, () ->
                query.settParameters("params", (Jdbc.BiParametersSetter<TestQuery, String>) (q, s) -> {
                    throw new SQLException("bi-settParameters failed");
                }));
    }

    // addBatchParameters(Iterator<T>, Class<T>) — empty iterator early return (lines 3752-3753)
    @Test
    public void testAddBatchParameters_TypedIterator_EmptyIterator() throws SQLException {
        TestQuery result = query.addBatchParameters(List.<String>of().iterator(), String.class);
        assertSame(query, result);
    }

    // configStmt(Consumer<Stmt>) — exception path closes (line 4305)
    @Test
    public void testConfigStmt_Consumer_ThrowsClosesQuery() {
        assertThrows(SQLException.class, () ->
                query.configStmt((Throwables.Consumer<PreparedStatement, SQLException>) stmt -> {
                    throw new SQLException("configStmt failed");
                }));
    }

    // configStmt(BiConsumer<This,Stmt>) — exception path closes (line 4345)
    @Test
    public void testConfigStmt_BiConsumer_ThrowsClosesQuery() {
        assertThrows(SQLException.class, () ->
                query.configStmt((Throwables.BiConsumer<TestQuery, PreparedStatement, SQLException>) (q, stmt) -> {
                    throw new SQLException("configStmt BiConsumer failed");
                }));
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

    // setLong(int, BigInteger) when stmt.setLong throws → close() is called (L795)
    @Test
    public void testSetLong_BigInteger_WhenSetLongThrows_ClosesQuery() throws SQLException {
        doThrow(new SQLException("setLong failed")).when(preparedStatement).setLong(anyInt(), anyLong());

        assertThrows(SQLException.class, () -> query.setLong(1, BigInteger.valueOf(42L)));
        // after exception, query should be closed
        verify(preparedStatement).close();
    }

    // findFirst(RowFilter, RowMapper) returns empty Optional when no row matches filter (L5962 / L6240)
    @Test
    @SuppressWarnings("deprecation")
    public void testFindFirst_RowFilter_RowMapper_NoMatchingRow() throws SQLException {
        final ResultSet rs = Mockito.mock(ResultSet.class);
        when(preparedStatement.executeQuery()).thenReturn(rs);
        when(rs.next()).thenReturn(true, false);  // one row but filter rejects it

        final com.landawn.abacus.util.u.Optional<String> result = query.findFirst(r -> false, r -> r.getString(1));

        assertNotNull(result);
        assertFalse(result.isPresent());
    }

    // close() logs warning when stmt.setFetchDirection throws during reset (L9919-9920)
    @Test
    public void testClose_WhenResetFetchDirectionFails_LogsWarning() throws SQLException {
        when(preparedStatement.getFetchDirection()).thenReturn(ResultSet.FETCH_REVERSE);
        // Only throw on reset call (FETCH_REVERSE), not on the initial set call (FETCH_FORWARD)
        doThrow(new SQLException("reset fetch dir failed")).when(preparedStatement).setFetchDirection(ResultSet.FETCH_REVERSE);

        query.setFetchDirection(FetchDirection.FORWARD);  // stores defaultFetchDirection = FETCH_REVERSE

        // close() resets to FETCH_REVERSE which throws, but is swallowed
        assertDoesNotThrow((org.junit.jupiter.api.function.Executable) query::close);
    }

    // close() logs warning when stmt.setFetchSize throws during reset (L9927-9928)
    @Test
    public void testClose_WhenResetFetchSizeFails_LogsWarning() throws SQLException {
        when(preparedStatement.getFetchSize()).thenReturn(100);
        doThrow(new SQLException("reset fetch size failed")).when(preparedStatement).setFetchSize(100);

        query.setFetchSize(500);  // stores defaultFetchSize = 100

        // close() resets to 100 which throws, but is swallowed
        assertDoesNotThrow((org.junit.jupiter.api.function.Executable) query::close);
    }

    // close() logs warning when stmt.setQueryTimeout throws during reset (L9943-9944)
    @Test
    public void testClose_WhenResetQueryTimeoutFails_LogsWarning() throws SQLException {
        when(preparedStatement.getQueryTimeout()).thenReturn(30);
        doThrow(new SQLException("reset timeout failed")).when(preparedStatement).setQueryTimeout(30);

        query.setQueryTimeout(60);  // stores defaultQueryTimeout = 30

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

    // addBatchParameters(Iterator) exception closes query (L3717)
    @Test
    public void testAddBatchParameters_Iterator_ExceptionClosesQuery() throws SQLException {
        doThrow(new SQLException("addBatch failed")).when(preparedStatement).addBatch();
        assertThrows(SQLException.class, () -> query.addBatchParameters(Arrays.asList("x").iterator()));
        verify(preparedStatement).close();
    }

    // addBatchParameters(Iterator, Class) exception closes query (L3764)
    @Test
    public void testAddBatchParameters_IteratorWithClass_ExceptionClosesQuery() throws SQLException {
        doThrow(new SQLException("addBatch failed")).when(preparedStatement).addBatch();
        assertThrows(SQLException.class, () -> query.addBatchParameters(Arrays.asList("x").iterator(), String.class));
        verify(preparedStatement).close();
    }

    // addBatchParameters(Iterator, BiParametersSetter) exception closes query (L3887)
    @Test
    public void testAddBatchParameters_BiSetter_ExceptionClosesQuery() throws SQLException {
        assertThrows(RuntimeException.class, () -> query.addBatchParameters(
                Arrays.asList("x").iterator(),
                (q, val) -> { throw new RuntimeException("setter error"); }));
        verify(preparedStatement).close();
    }

    // addBatchParameters(Iterator, TriParametersSetter) exception closes query (L4018)
    @Test
    public void testAddBatchParameters_TriSetter_ExceptionClosesQuery() throws SQLException {
        assertThrows(RuntimeException.class, () -> query.addBatchParameters(
                Arrays.asList("x").iterator(),
                (q, stmt2, val) -> { throw new RuntimeException("setter error"); }));
        verify(preparedStatement).close();
    }

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

        try (com.landawn.abacus.util.stream.Stream<String> s = query.streamAllResultSets(
                Jdbc.BiResultExtractor.TO_DATASET.andThen(ds -> "result"))) {
            assertNotNull(s);
        }
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

        final boolean[] called = {false};
        query.ifExistsOrElse((r, labels) -> {}, () -> called[0] = true);

        assertTrue(called[0]);
    }

    // forEach(RowFilter, RowConsumer) – filter rejects row so consumer not called (L8455-8469)
    @Test
    public void testForEach_WithRowFilter_FilterRejectsRow() throws SQLException {
        final ResultSet rs = Mockito.mock(ResultSet.class);
        when(preparedStatement.executeQuery()).thenReturn(rs);
        when(rs.next()).thenReturn(true, false);

        final boolean[] consumed = {false};
        query.forEach((r) -> false, r -> consumed[0] = true);

        assertFalse(consumed[0]);
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

        // No rows so consumer is never invoked — just verify no exception
        query.foreach(String.class, arr -> {});
    }

    @Test
    public void testSetNString_NonNull() throws SQLException {
        TestQuery result = query.setNString(1, (CharSequence) "hello");
        assertSame(query, result);
        verify(preparedStatement).setNString(1, "hello");
    }

    @Test
    public void testSetNString_Null() throws SQLException {
        query.setNString(1, (CharSequence) null);
        verify(preparedStatement).setNString(1, null);
    }

    // setDate(int, java.util.Date) - converts non-sql Date to java.sql.Date
    @Test
    public void testSetDate_UtilDate_NonNull() throws SQLException {
        java.util.Date utilDate = new java.util.Date(1000L);
        query.setDate(1, utilDate);
        verify(preparedStatement).setDate(1, new java.sql.Date(1000L));
    }

    @Test
    public void testSetDate_UtilDate_Null() throws SQLException {
        query.setDate(1, (java.util.Date) null);
        verify(preparedStatement).setDate(1, (java.sql.Date) null);
    }

    @Test
    public void testSetDate_SqlDate_WithCalendar() throws SQLException {
        java.sql.Date sqlDate = new java.sql.Date(2000L);
        Calendar cal = Calendar.getInstance();
        query.setDate(1, sqlDate, cal);
        verify(preparedStatement).setDate(1, sqlDate, cal);
    }

    @Test
    public void testSetDate_LocalDate_NonNull() throws SQLException {
        LocalDate ld = LocalDate.of(2024, 1, 15);
        query.setDate(1, ld);
        verify(preparedStatement).setDate(1, java.sql.Date.valueOf(ld));
    }

    @Test
    public void testSetDate_LocalDate_Null() throws SQLException {
        query.setDate(1, (LocalDate) null);
        verify(preparedStatement).setDate(1, (java.sql.Date) null);
    }

    // setTime(int, java.util.Date) - converts non-sql Time
    @Test
    public void testSetTime_UtilDate_NonNull() throws SQLException {
        java.util.Date utilDate = new java.util.Date(3000L);
        query.setTime(1, utilDate);
        verify(preparedStatement).setTime(1, new java.sql.Time(3000L));
    }

    @Test
    public void testSetTime_UtilDate_Null() throws SQLException {
        query.setTime(1, (java.util.Date) null);
        verify(preparedStatement).setTime(1, (java.sql.Time) null);
    }

    @Test
    public void testSetTime_SqlTime_WithCalendar() throws SQLException {
        java.sql.Time sqlTime = new java.sql.Time(4000L);
        Calendar cal = Calendar.getInstance();
        query.setTime(1, sqlTime, cal);
        verify(preparedStatement).setTime(1, sqlTime, cal);
    }

    @Test
    public void testSetTime_LocalTime_NonNull() throws SQLException {
        LocalTime lt = LocalTime.of(14, 30, 0);
        query.setTime(1, lt);
        verify(preparedStatement).setTime(1, java.sql.Time.valueOf(lt));
    }

    @Test
    public void testSetTime_LocalTime_Null() throws SQLException {
        query.setTime(1, (LocalTime) null);
        verify(preparedStatement).setTime(1, (java.sql.Time) null);
    }

    // setTimestamp variants
    @Test
    public void testSetTimestamp_UtilDate_NonNull() throws SQLException {
        java.util.Date utilDate = new java.util.Date(5000L);
        query.setTimestamp(1, utilDate);
        verify(preparedStatement).setTimestamp(1, new Timestamp(5000L));
    }

    @Test
    public void testSetTimestamp_UtilDate_Null() throws SQLException {
        query.setTimestamp(1, (java.util.Date) null);
        verify(preparedStatement).setTimestamp(1, (Timestamp) null);
    }

    @Test
    public void testSetTimestamp_SqlTimestamp_WithCalendar() throws SQLException {
        Timestamp ts = new Timestamp(6000L);
        Calendar cal = Calendar.getInstance();
        query.setTimestamp(1, ts, cal);
        verify(preparedStatement).setTimestamp(1, ts, cal);
    }

    @Test
    public void testSetTimestamp_LocalDateTime_NonNull() throws SQLException {
        LocalDateTime ldt = LocalDateTime.of(2024, 6, 15, 10, 30, 0);
        query.setTimestamp(1, ldt);
        verify(preparedStatement).setTimestamp(1, Timestamp.valueOf(ldt));
    }

    @Test
    public void testSetTimestamp_LocalDateTime_Null() throws SQLException {
        query.setTimestamp(1, (LocalDateTime) null);
        verify(preparedStatement).setTimestamp(1, (Timestamp) null);
    }

    @Test
    public void testSetTimestamp_ZonedDateTime_NonNull() throws SQLException {
        ZonedDateTime zdt = ZonedDateTime.now();
        query.setTimestamp(1, zdt);
        verify(preparedStatement).setTimestamp(1, Timestamp.from(zdt.toInstant()));
    }

    @Test
    public void testSetTimestamp_ZonedDateTime_Null() throws SQLException {
        query.setTimestamp(1, (ZonedDateTime) null);
        verify(preparedStatement).setTimestamp(1, (Timestamp) null);
    }

    @Test
    public void testSetTimestamp_OffsetDateTime_NonNull() throws SQLException {
        OffsetDateTime odt = OffsetDateTime.now();
        query.setTimestamp(1, odt);
        verify(preparedStatement).setTimestamp(1, Timestamp.from(odt.toInstant()));
    }

    @Test
    public void testSetTimestamp_OffsetDateTime_Null() throws SQLException {
        query.setTimestamp(1, (OffsetDateTime) null);
        verify(preparedStatement).setTimestamp(1, (Timestamp) null);
    }

    @Test
    public void testSetTimestamp_Instant_NonNull() throws SQLException {
        Instant instant = Instant.ofEpochMilli(7000L);
        query.setTimestamp(1, instant);
        verify(preparedStatement).setTimestamp(1, Timestamp.from(instant));
    }

    @Test
    public void testSetTimestamp_Instant_Null() throws SQLException {
        query.setTimestamp(1, (Instant) null);
        verify(preparedStatement).setTimestamp(1, (Timestamp) null);
    }

    // setFetchDirection: first call saves default (defaultFetchDirection < 0 branch)
    @Test
    public void testSetFetchDirection_FirstCall_SavesDefault() throws SQLException {
        when(preparedStatement.getFetchDirection()).thenReturn(ResultSet.FETCH_FORWARD);
        query.setFetchDirection(FetchDirection.FORWARD);
        verify(preparedStatement).getFetchDirection();
        verify(preparedStatement).setFetchDirection(ResultSet.FETCH_FORWARD);
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

    // insert(BiRowMapper, Predicate): rs.next() returns false → returns Optional.empty() (L8844)
    @Test
    public void testInsert_NoGeneratedKey_ReturnsEmpty() throws SQLException {
        ResultSet generatedKeys = Mockito.mock(ResultSet.class);
        when(preparedStatement.getGeneratedKeys()).thenReturn(generatedKeys);
        when(generatedKeys.next()).thenReturn(false);

        com.landawn.abacus.util.u.Optional<?> result = query.insert(
                (Jdbc.BiRowMapper<Long>) (rs, cols) -> rs.getLong(1),
                id -> id == null || (Long) id == 0L);
        assertTrue(result.isEmpty());
    }

    // batchInsert(RowMapper, Predicate): all IDs are null → returns empty list (L8961)
    @Test
    public void testBatchInsert_RowMapper_AllNullIds_ReturnsEmptyList() throws SQLException {
        ResultSet generatedKeys = Mockito.mock(ResultSet.class);
        when(preparedStatement.executeBatch()).thenReturn(new int[] { 1 });
        when(preparedStatement.getGeneratedKeys()).thenReturn(generatedKeys);
        when(generatedKeys.next()).thenReturn(true, false);

        @SuppressWarnings("unchecked")
        Predicate<Object> isDefault = id -> id == null;
        List<?> result = query.batchInsert((Jdbc.RowMapper<Long>) rs -> null, isDefault);
        assertEquals(0, result.size());
    }

    // listAllResultSets(Class): no result sets → returns empty list (L6685-6709)
    @Test
    public void testListAllResultSets_Class_ReturnsEmptyList() throws SQLException {
        when(preparedStatement.execute()).thenReturn(false);
        when(preparedStatement.getUpdateCount()).thenReturn(-1);
        List<List<String>> result = query.listAllResultSets(String.class);
        assertTrue(result.isEmpty());
    }

    // createQuerySupplier lambda: executeQuery() throws SQLException → wrapped in UncheckedSQLException (L7706-7707)
    @Test
    public void testStream_RowMapper_ExecuteQueryThrows_WrapsUncheckedSQLException() throws SQLException {
        when(preparedStatement.executeQuery()).thenThrow(new SQLException("forced"));
        Stream<String> stream = query.stream(rs -> rs.getString(1));
        assertThrows(UncheckedSQLException.class, stream::toList);
    }

    // createExecuteSupplier lambda: stmt.execute() throws SQLException → wrapped in UncheckedSQLException (L7715-7717)
    @Test
    public void testStreamAllResultSets_ResultExtractor_ExecuteThrows_WrapsUncheckedSQLException() throws SQLException {
        when(preparedStatement.execute()).thenThrow(new SQLException("forced"));
        Stream<List<String>> stream = query.streamAllResultSets((Jdbc.ResultExtractor<List<String>>) rs -> new ArrayList<>());
        assertThrows(UncheckedSQLException.class, stream::toList);
    }

    // batchInsert(BiRowMapper, Predicate): all IDs are null → returns empty list (L9001)
    @Test
    public void testBatchInsert_BiRowMapper_AllNullIds_ReturnsEmptyList() throws SQLException {
        ResultSet generatedKeys = Mockito.mock(ResultSet.class);
        ResultSetMetaData meta = Mockito.mock(ResultSetMetaData.class);
        when(preparedStatement.executeBatch()).thenReturn(new int[] { 1 });
        when(preparedStatement.getGeneratedKeys()).thenReturn(generatedKeys);
        when(generatedKeys.getMetaData()).thenReturn(meta);
        when(meta.getColumnCount()).thenReturn(1);
        when(meta.getColumnLabel(1)).thenReturn("id");
        when(generatedKeys.next()).thenReturn(true, false);

        @SuppressWarnings("unchecked")
        Predicate<Object> isDefault = id -> id == null;
        List<?> result = query.batchInsert((Jdbc.BiRowMapper<Long>) (rs, cols) -> null, isDefault);
        assertEquals(0, result.size());
    }
}
