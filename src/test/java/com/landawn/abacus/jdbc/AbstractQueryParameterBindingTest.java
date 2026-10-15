package com.landawn.abacus.jdbc;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
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

import java.math.BigInteger;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.SQLType;
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
import java.util.Calendar;
import java.util.List;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import com.landawn.abacus.type.Type;
import com.landawn.abacus.type.TypeFactory;

class AbstractQueryParameterBindingTest extends AbstractQueryTestSupport {

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
        TestQuery result = query.setShort(1, (short) 7, (short) 0);
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

    // setLong(int, BigInteger) when stmt.setLong throws → close() is called (L795)
    @Test
    public void testSetLong_BigInteger_WhenSetLongThrows_ClosesQuery() throws SQLException {
        doThrow(new SQLException("setLong failed")).when(preparedStatement).setLong(anyInt(), anyLong());

        assertThrows(SQLException.class, () -> query.setLong(1, BigInteger.valueOf(42L)));
        // after exception, query should be closed
        verify(preparedStatement).close();
    }

    @Test
    public void testSetLongBigIntegerPreservesConversionFailureWhenCloseHandlerThrows() {
        final RuntimeException closeFailure = new RuntimeException("close handler failed");
        query.onClose(() -> {
            throw closeFailure;
        });

        final ArithmeticException thrown = assertThrows(ArithmeticException.class,
                () -> query.setLong(1, BigInteger.valueOf(Long.MAX_VALUE).add(BigInteger.ONE)));

        assertEquals(1, thrown.getSuppressed().length);
        assertSame(closeFailure, thrown.getSuppressed()[0]);
    }

    // Tests for setFloat with null wrapper
    @Test
    public void testSetFloat_Wrapper_Null() throws SQLException {
        TestQuery result = query.setFloat(1, (Float) null);
        assertSame(query, result);
        // Per JDBC spec, Java float maps to SQL REAL. (Types.FLOAT is the alias for Types.DOUBLE.)
        verify(preparedStatement).setNull(1, Types.REAL);
    }

    @Test
    public void testSetFloat_Wrapper_NonNull() throws SQLException {
        TestQuery result = query.setFloat(1, (Float) 1.5f);
        assertSame(query, result);
        verify(preparedStatement).setFloat(1, 1.5f);
    }

    // setFloat(int, Float, float) - non-null branch (line 867) was not covered
    @Test
    public void testSetFloat_WithDefault_NonNull() throws SQLException {
        TestQuery result = query.setFloat(1, Float.valueOf(2.5f), 0.0f);
        assertSame(query, result);
        verify(preparedStatement).setFloat(1, 2.5f);
    }

    // TODO: L142 (static initializer) - uncovered branch requires method with parameterTypes[0] != int, edge case not testable
    // TODO: L7427, L7477 (stream(RowFilter/BiRowFilter) lambdas) - partially covered, requires stream consumption through JdbcUtil static methods
    // TODO: L7688-L7689, L7741-L7742 (streamAllResultSets(Extractor/BiExtractor) lambdas) - partially covered, requires stream consumption through JdbcUtil static methods

    // Regression: setFloat(int, Float) used Types.FLOAT for SQL null. Per JDBC spec Appendix B.4,
    // Java float maps to SQL REAL (4-byte); Types.FLOAT is an alias for Types.DOUBLE (8-byte).
    // Strict drivers reject the SQL-type/value mismatch or coerce, losing precision metadata.
    // Sibling setDouble correctly uses Types.DOUBLE.
    @Test
    public void testSetFloat_NullUsesTypesREAL() throws SQLException {
        query.setFloat(1, (Float) null);
        verify(preparedStatement).setNull(1, Types.REAL);
        // Pre-fix this was setNull(1, Types.FLOAT) — make sure the wrong type is NOT used.
        verify(preparedStatement, never()).setNull(1, Types.FLOAT);
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

    // setDouble(int, Double, double) - non-null branch (line 965) was not covered
    @Test
    public void testSetDouble_WithDefault_NonNull() throws SQLException {
        TestQuery result = query.setDouble(1, Double.valueOf(1.23), 0.0);
        assertSame(query, result);
        verify(preparedStatement).setDouble(1, 1.23);
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

    @Test
    public void testSetDate_SqlDate_WithCalendar() throws SQLException {
        java.sql.Date sqlDate = new java.sql.Date(2000L);
        Calendar cal = Calendar.getInstance();
        query.setDate(1, sqlDate, cal);
        verify(preparedStatement).setDate(1, sqlDate, cal);
    }

    // setDate(int, java.util.Date) with java.sql.Date instance when instanceof check is true (L1251)
    @Test
    public void testSetDate_UtilDate_WithSqlDateInstance() throws SQLException {
        final java.sql.Date sqlDate = new java.sql.Date(5000L);
        query.setDate(1, (java.util.Date) sqlDate);
        verify(preparedStatement).setDate(1, sqlDate);
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

    @Test
    public void testSetTime_SqlTime_WithCalendar() throws SQLException {
        java.sql.Time sqlTime = new java.sql.Time(4000L);
        Calendar cal = Calendar.getInstance();
        query.setTime(1, sqlTime, cal);
        verify(preparedStatement).setTime(1, sqlTime, cal);
    }

    // setTime(int, java.util.Date) with java.sql.Time instance when instanceof check is true (L1333)
    @Test
    public void testSetTime_UtilDate_WithSqlTimeInstance() throws SQLException {
        final java.sql.Time sqlTime = new java.sql.Time(6000L);
        query.setTime(1, (java.util.Date) sqlTime);
        verify(preparedStatement).setTime(1, sqlTime);
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

    @Test
    public void testSetTimestamp_SqlTimestamp_WithCalendar() throws SQLException {
        Timestamp ts = new Timestamp(6000L);
        Calendar cal = Calendar.getInstance();
        query.setTimestamp(1, ts, cal);
        verify(preparedStatement).setTimestamp(1, ts, cal);
    }

    // setTimestamp(int, java.util.Date) with java.sql.Timestamp instance when instanceof check is true (L1415)
    @Test
    public void testSetTimestamp_UtilDate_WithSqlTimestampInstance() throws SQLException {
        final java.sql.Timestamp sqlTs = new java.sql.Timestamp(7000L);
        query.setTimestamp(1, (java.util.Date) sqlTs);
        verify(preparedStatement).setTimestamp(1, sqlTs);
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

    @Test
    public void testSetObject_WithType_DelegatesToType() throws SQLException {
        final Type<String> strType = TypeFactory.getType(String.class);

        TestQuery result = query.setObject(1, "hello", strType);

        assertSame(query, result);
        verify(preparedStatement).setString(1, "hello");
    }

    // setObject(int, Object, int) validates sqlType against the standard java.sql.Types / JDBCType
    // constants (rejecting garbage up front with a clear IAE) and routes a null value through the spec'd
    // setNull path rather than setObject(i, null, sqlType).
    @Test
    public void testSetObject_IntSqlType_NonNull_PassesThrough() throws SQLException {
        query.setObject(1, "x", Types.VARCHAR);

        verify(preparedStatement).setObject(1, "x", Types.VARCHAR);
        verify(preparedStatement, never()).setNull(1, Types.VARCHAR);
    }

    @Test
    public void testSetObject_IntSqlType_Null_RoutesToSetNull() throws SQLException {
        query.setObject(1, null, Types.VARCHAR);

        verify(preparedStatement).setNull(1, Types.VARCHAR);
        verify(preparedStatement, never()).setObject(1, null, Types.VARCHAR);
    }

    @Test
    public void testSetObject_IntSqlType_Invalid_ThrowsIAE() {
        // 99999 is not a standard java.sql.Types / JDBCType constant.
        assertThrows(IllegalArgumentException.class, () -> query.setObject(1, "x", 99999));
    }

    @Test
    public void testSetObject_IntSqlType_Scale_Invalid_ThrowsIAE() {
        // The 4-arg int overload validates sqlType too.
        assertThrows(IllegalArgumentException.class, () -> query.setObject(1, "x", 99999, 2));
    }

    // Close-before-throw contract: an invalid sqlType closes the query (checkSqlType → closeSuppressingFailure),
    // so a later execution attempt fails with ISE instead of running on a half-configured statement.
    @Test
    @Tag("2025")
    public void testSetObject_IntSqlType_Invalid_ClosesQuery() {
        assertThrows(IllegalArgumentException.class, () -> query.setObject(1, "x", 99999));

        assertTrue(query.isClosed);
        assertThrows(IllegalStateException.class, () -> query.update());
    }

    @Test
    public void testSetObject_SQLType_Null_ThrowsIAE() {
        assertThrows(IllegalArgumentException.class, () -> query.setObject(1, "x", (SQLType) null));
    }

    @Test
    public void testSetParameters_IntArray() throws SQLException {
        TestQuery result = query.setParameters(new int[] { 1, 2, 3 });

        assertSame(query, result);
        verify(preparedStatement).setInt(1, 1);
        verify(preparedStatement).setInt(2, 2);
        verify(preparedStatement).setInt(3, 3);
    }

    // setParameters(ParametersSetter<Stmt>) — exception path closes the query (line 2791)
    @Test
    public void testSetParameters_ParametersSetter_ThrowsClosesQuery() {
        assertThrows(SQLException.class, () -> query.setParameters((Jdbc.ParametersSetter<PreparedStatement>) stmt -> {
            throw new SQLException("setter failed");
        }));
    }

    @Test
    public void testSetParametersPreservesPrimaryFailureWhenCloseHandlerThrowsSameInstance() {
        final RuntimeException primaryFailure = new RuntimeException("setter and close failed");
        query.onClose(() -> {
            throw primaryFailure;
        });

        final RuntimeException thrown = assertThrows(RuntimeException.class, () -> query.setParameters((Jdbc.ParametersSetter<PreparedStatement>) stmt -> {
            throw primaryFailure;
        }));

        assertSame(primaryFailure, thrown);
        assertEquals(0, thrown.getSuppressed().length);
    }

    // setParameters(T, BiParametersSetter<Stmt,T>) — exception path closes (line 2855)
    @Test
    public void testSetParameters_BiParametersSetter_ThrowsClosesQuery() {
        assertThrows(SQLException.class, () -> query.setParameters("data", (Jdbc.BiParametersSetter<PreparedStatement, String>) (stmt, s) -> {
            throw new SQLException("bi-setter failed");
        }));
    }

    @Test
    public void testSetParametersFrom_Collection() throws SQLException {
        TestQuery result = query.setParametersFrom(2, List.of("a", "b"));

        assertSame(query, result);
        verify(preparedStatement).setString(2, "a");
        verify(preparedStatement).setString(3, "b");
    }

    // A null element takes the setObject(idx, null) branch (AbstractQuery L3021) rather than the
    // type-resolved path; non-null neighbours still use their resolved Type.
    @Test
    public void testSetParametersFrom_Collection_WithNullElement() throws SQLException {
        TestQuery result = query.setParametersFrom(1, Arrays.asList("a", null, "b"));

        assertSame(query, result);
        verify(preparedStatement).setString(1, "a");
        verify(preparedStatement).setObject(2, null);
        verify(preparedStatement).setString(3, "b");
    }

    @Test
    public void testSetParametersFromRejectsNonPositiveStartIndexForEmptyInput() throws SQLException {
        final IllegalArgumentException thrown = assertThrows(IllegalArgumentException.class, () -> query.setParametersFrom(0, new int[0]));

        assertTrue(thrown.getMessage().contains("startParameterIndex"));
        assertTrue(query.isClosed);
        verify(preparedStatement, never()).setInt(anyInt(), anyInt());
    }

    // settParameters(ParametersSetter<This>) — exception path closes (line 3117)
    @Test
    public void testSettParameters_ParametersSetter_ThrowsClosesQuery() {
        assertThrows(SQLException.class, () -> query.settParameters((Jdbc.ParametersSetter<TestQuery>) q -> {
            throw new SQLException("settParameters failed");
        }));
    }

    // settParameters(T, BiParametersSetter<This,T>) — exception path closes (line 3165)
    @Test
    public void testSettParameters_BiParametersSetter_ThrowsClosesQuery() {
        assertThrows(SQLException.class, () -> query.settParameters("params", (Jdbc.BiParametersSetter<TestQuery, String>) (q, s) -> {
            throw new SQLException("bi-settParameters failed");
        }));
    }

    @Test
    public void testSetNullForIndices() throws SQLException {
        TestQuery result = query.setNullForIndices(Types.INTEGER, 1, 3);

        assertSame(query, result);
        verify(preparedStatement).setNull(1, Types.INTEGER);
        verify(preparedStatement).setNull(3, Types.INTEGER);
    }

    @Test
    public void testAddBatchParameters_IteratorOfScalarValues() throws SQLException {
        TestQuery result = query.addBatchParameters(List.of(1, 2).iterator());

        assertSame(query, result);
        verify(preparedStatement).setInt(1, 1);
        verify(preparedStatement).setInt(1, 2);
        verify(preparedStatement, times(2)).addBatch();
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

    // ---- addBatchParameters(Iterator) must clear parameters before every row ----
    // Regression: AbstractQuery.addBatchParameters(Iterator) iterated setParameters(Collection)/setParameters(Object[])
    // without calling stmt.clearParameters() first — so when a follow-up row was shorter than the first, positions
    // beyond the new row's length retained values from the earlier row. AbstractQuery now clears before every row
    // (including the first, to also drop any positions pre-bound via setXxx), matching NamedQuery's pattern.

    @Test
    public void testAddBatchParameters_Iterator_Collection_ClearsBetweenRows() throws SQLException {
        final java.util.List<java.util.Collection<?>> rows = new java.util.ArrayList<>();
        rows.add(java.util.Arrays.asList("a", "b", "c"));
        rows.add(java.util.Arrays.asList("x", "y"));

        query.addBatchParameters(rows.iterator());

        // clearParameters() is called before every row (including the first) — twice for two rows.
        verify(preparedStatement, times(2)).clearParameters();
        // Both rows reached addBatch.
        verify(preparedStatement, times(2)).addBatch();
    }

    @Test
    public void testAddBatchParameters_Iterator_ObjectArray_ClearsBetweenRows() throws SQLException {
        final java.util.List<Object[]> rows = new java.util.ArrayList<>();
        rows.add(new Object[] { "a", "b", "c" });
        rows.add(new Object[] { "x", "y" });

        query.addBatchParameters(rows.iterator());

        verify(preparedStatement, times(2)).clearParameters();
        verify(preparedStatement, times(2)).addBatch();
    }

    // A null FIRST row fixes the single-value shape (documented): later collection rows are bound as one
    // value at position 1, parameters are never cleared, and no IllegalArgumentException is raised.
    @Test
    public void testAddBatchParameters_Iterator_NullFirstRow_FixesSingleValueShape() throws SQLException {
        final List<Object> rows = new ArrayList<>();
        rows.add(null);
        rows.add(List.of("a", "b"));

        assertSame(query, query.addBatchParameters(rows.iterator()));

        verify(preparedStatement).setObject(1, null);
        verify(preparedStatement, never()).setString(2, "b");
        verify(preparedStatement, never()).clearParameters();
        verify(preparedStatement, times(2)).addBatch();
        assertFalse(query.isClosed);
    }

    // An empty typed iterator adds no batch rows and leaves the query open and reusable.
    @Test
    @Tag("2025")
    public void testAddBatchParameters_TypedIterator_Empty_NoOp() throws SQLException {
        final TestQuery result = query.addBatchParameters(List.<String> of().iterator(), String.class);

        assertSame(query, result);
        assertFalse(query.isClosed);
        verify(preparedStatement, never()).addBatch();
    }

    @Test
    public void testAddBatchParameters_EmptyCollection() throws SQLException {
        TestQuery result = query.addBatchParameters(List.of());

        assertSame(query, result);
        verify(preparedStatement, never()).addBatch();
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

    // addBatchParameters(Iterator<T>, Class<T>) — empty iterator early return (lines 3752-3753)
    @Test
    public void testAddBatchParameters_TypedIterator_EmptyIterator() throws SQLException {
        TestQuery result = query.addBatchParameters(List.<String> of().iterator(), String.class);
        assertSame(query, result);
    }

    // Mixed-shape batch rows: the first element fixes the row shape; a later element of a different
    // shape fails with ClassCastException and the query closes on the failure path.
    @Test
    @Tag("2025")
    public void testAddBatchParameters_Iterator_MixedShapes_ClosesQuery() {
        final List<Object> rows = new ArrayList<>();
        rows.add(List.of("a"));
        rows.add(new Object[] { "b" });

        assertThrows(ClassCastException.class, () -> query.addBatchParameters(rows.iterator()));
        assertTrue(query.isClosed);
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
        assertThrows(RuntimeException.class, () -> query.addBatchParameters(Arrays.asList("x").iterator(), (q, val) -> {
            throw new RuntimeException("setter error");
        }));
        verify(preparedStatement).close();
    }

    // addBatchParameters(Iterator, TriParametersSetter) exception closes query (L4018)
    @Test
    public void testAddBatchParameters_TriSetter_ExceptionClosesQuery() throws SQLException {
        assertThrows(RuntimeException.class, () -> query.addBatchParameters(Arrays.asList("x").iterator(), (q, stmt2, val) -> {
            throw new RuntimeException("setter error");
        }));
        verify(preparedStatement).close();
    }

    // setFetchDirection: first call saves default (defaultFetchDirection < 0 branch)
    @Test
    public void testSetFetchDirection_FirstCall_SavesDefault() throws SQLException {
        when(preparedStatement.getFetchDirection()).thenReturn(ResultSet.FETCH_FORWARD);
        query.setFetchDirection(FetchDirection.FORWARD);
        verify(preparedStatement).getFetchDirection();
        verify(preparedStatement).setFetchDirection(ResultSet.FETCH_FORWARD);
    }

    // setFetchDirection second call → default already saved, covers PC L4130 else branch
    @Test
    public void testSetFetchDirection_SecondCall_DoesNotResaveDefault() throws SQLException {
        when(preparedStatement.getFetchDirection()).thenReturn(ResultSet.FETCH_FORWARD);
        query.setFetchDirection(FetchDirection.FORWARD);
        query.setFetchDirection(FetchDirection.REVERSE);
        verify(preparedStatement, times(1)).getFetchDirection();
    }

    @Test
    public void testSetFetchSize() throws SQLException {
        TestQuery result = query.setFetchSize(-1);

        assertSame(query, result);
        verify(preparedStatement).setFetchSize(-1);
    }

    // setFetchSize second call → default already saved, covers PC L4180 else branch
    @Test
    public void testSetFetchSize_SecondCall_DoesNotResaveDefault() throws SQLException {
        when(preparedStatement.getFetchSize()).thenReturn(100);
        query.setFetchSize(50);
        query.setFetchSize(200);
        verify(preparedStatement, times(1)).getFetchSize();
    }

    // A failure while capturing the original value (before anything is changed) closes the query too.
    @Test
    public void testSetFetchSize_CaptureFailure_ClosesQuery() throws SQLException {
        final SQLException captureFailure = new SQLException("getFetchSize failed");
        when(preparedStatement.getFetchSize()).thenThrow(captureFailure);

        assertSame(captureFailure, assertThrows(SQLException.class, () -> query.setFetchSize(100)));

        verify(preparedStatement, never()).setFetchSize(anyInt());
        verify(preparedStatement).close();
    }

    // setMaxFieldSize second call → default already saved, covers PC L4208 else branch
    @Test
    public void testSetMaxFieldSize_SecondCall_DoesNotResaveDefault() throws SQLException {
        when(preparedStatement.getMaxFieldSize()).thenReturn(1024);
        query.setMaxFieldSize(512);
        query.setMaxFieldSize(2048);
        verify(preparedStatement, times(1)).getMaxFieldSize();
    }

    // setMaxRows second call should reuse the captured default (covers the
    // `if (defaultMaxRows < 0)` else branch).
    @Test
    public void testSetMaxRows_SecondCallDoesNotRecaptureDefault() throws SQLException {
        when(preparedStatement.getMaxRows()).thenReturn(50);

        query.setMaxRows(200);
        query.setMaxRows(300);

        // getMaxRows captured exactly once
        verify(preparedStatement, times(1)).getMaxRows();
        verify(preparedStatement).setMaxRows(200);
        verify(preparedStatement).setMaxRows(300);

        query.close();

        verify(preparedStatement).setMaxRows(50); // still restored to original
    }

    @Test
    public void testSetLargeMaxRows_SecondCallDoesNotRecaptureDefault() throws SQLException {
        when(preparedStatement.getLargeMaxRows()).thenReturn(75L);

        query.setLargeMaxRows(500L);
        query.setLargeMaxRows(600L);

        verify(preparedStatement, times(1)).getLargeMaxRows();
        verify(preparedStatement).setLargeMaxRows(500L);
        verify(preparedStatement).setLargeMaxRows(600L);

        query.close();

        verify(preparedStatement).setLargeMaxRows(75L);
    }

    // Regression: a driver without setLargeMaxRows support (JDBC 4.2 default throws UnsupportedOperationException)
    // used to leave the captured default behind, so closing the query replayed setLargeMaxRows and hit the UOE again.
    @Test
    public void testSetLargeMaxRows_UnsupportedByDriver_DoesNotReplayOnClose() throws SQLException {
        final UnsupportedOperationException unsupported = new UnsupportedOperationException("setLargeMaxRows not implemented");
        when(preparedStatement.getLargeMaxRows()).thenReturn(75L);
        doThrow(unsupported).when(preparedStatement).setLargeMaxRows(anyLong());

        final UnsupportedOperationException thrown = assertThrows(UnsupportedOperationException.class, () -> query.setLargeMaxRows(500L));

        assertSame(unsupported, thrown);
        assertEquals(0, thrown.getSuppressed().length);
        verify(preparedStatement, times(1)).setLargeMaxRows(anyLong()); // only the user's failed call; no replay on close
        verify(preparedStatement).close();
        assertDoesNotThrow((org.junit.jupiter.api.function.Executable) query::close);
    }

    // setQueryTimeout second call → default already saved, covers PC L4281 else branch
    @Test
    public void testSetQueryTimeout_SecondCall_DoesNotResaveDefault() throws SQLException {
        when(preparedStatement.getQueryTimeout()).thenReturn(30);
        query.setQueryTimeout(10);
        query.setQueryTimeout(60);
        verify(preparedStatement, times(1)).getQueryTimeout();
    }
}
