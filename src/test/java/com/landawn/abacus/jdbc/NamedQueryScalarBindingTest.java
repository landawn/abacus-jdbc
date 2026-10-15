package com.landawn.abacus.jdbc;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.sql.SQLException;
import java.sql.Types;

import org.junit.jupiter.api.Test;

import com.landawn.abacus.util.ImmutableList;

class NamedQueryScalarBindingTest extends NamedQueryTestSupport {

    @Test
    public void testSetBooleanPrimitive() throws SQLException {
        String paramName = "param1";
        boolean value = true;

        NamedQuery result = namedQuery.setBoolean(paramName, value);

        verify(mockPreparedStatement).setBoolean(1, value);
        assertSame(namedQuery, result);
    }

    @Test
    public void testSetBooleanObject() throws SQLException {
        String paramName = "param1";
        Boolean value = Boolean.FALSE;

        NamedQuery result = namedQuery.setBoolean(paramName, value);

        verify(mockPreparedStatement).setBoolean(1, false);
        assertSame(namedQuery, result);
    }

    @Test
    public void testSetBoolean_MapPath() throws Exception {
        NamedQuery q = createManyParamQuery();
        NamedQuery result = q.setBoolean("a", true);
        verify(mockPreparedStatement).setBoolean(1, true);
        assertSame(q, result);
    }

    @Test
    public void testSetBoolean_MapPath_NotFound() throws Exception {
        NamedQuery q = createManyParamQuery();
        assertThrows(IllegalArgumentException.class, () -> q.setBoolean("unknown", true));
    }

    // Test triple duplicate params in index-by-map path (size==3)
    @Test
    public void testSetBoolean_MapPath_TripleDuplicateParam() throws Exception {
        when(mockParsedSql.namedParameters()).thenReturn(ImmutableList.of("a", "b", "c", "a", "a"));
        when(mockParsedSql.parameterCount()).thenReturn(5);
        when(mockParsedSql.originalSql()).thenReturn("SELECT 1");
        NamedQuery q = new NamedQuery(mockPreparedStatement, mockParsedSql);
        q.setBoolean("a", false);
        verify(mockPreparedStatement).setBoolean(1, false);
        verify(mockPreparedStatement).setBoolean(4, false);
        verify(mockPreparedStatement).setBoolean(5, false);
    }

    @Test
    public void testSetBoolean_LoopBranch_ParamNotFound() throws SQLException {
        // paramCount < 5, setBoolean, param not found → lines 283-284
        assertThrows(IllegalArgumentException.class, () -> namedQuery.setBoolean("unknown", true));
    }

    @Test
    public void testSetBoolean_MapBranch_TwoOccurrences() throws SQLException {
        // paramCount >= 5, setBoolean, 2 occurrences → lines 300-301
        NamedQuery q = createMapBranchQuery(ImmutableList.of("a", "b", "c", "p", "p"));
        q.setBoolean("p", true);
        verify(mockPreparedStatement, times(2)).setBoolean(anyInt(), eq(true));
    }

    @Test
    public void testSetBoolean_MapBranch_FourOccurrences() throws SQLException {
        // paramCount >= 5, setBoolean, 4 occurrences → for loop (line 308)
        NamedQuery q = createMapBranchQuery(ImmutableList.of("a", "p", "p", "p", "p"));
        q.setBoolean("p", false);
        verify(mockPreparedStatement, times(4)).setBoolean(anyInt(), eq(false));
    }

    @Test
    public void testSetBooleanObjectNull() throws SQLException {
        String paramName = "param1";
        Boolean value = null;

        NamedQuery result = namedQuery.setBoolean(paramName, value);

        verify(mockPreparedStatement).setNull(1, Types.BOOLEAN);
        assertSame(namedQuery, result);
    }

    @Test
    public void testSetBytePrimitive() throws SQLException {
        String paramName = "param1";
        byte value = 100;

        NamedQuery result = namedQuery.setByte(paramName, value);

        verify(mockPreparedStatement).setByte(1, value);
        assertSame(namedQuery, result);
    }

    @Test
    public void testSetByteObject() throws SQLException {
        String paramName = "param1";
        Byte value = Byte.valueOf((byte) 50);

        NamedQuery result = namedQuery.setByte(paramName, value);

        verify(mockPreparedStatement).setByte(1, (byte) 50);
        assertSame(namedQuery, result);
    }

    @Test
    public void testSetByte_MapPath() throws Exception {
        NamedQuery q = createManyParamQuery();
        NamedQuery result = q.setByte("c", (byte) 7);
        verify(mockPreparedStatement).setByte(3, (byte) 7);
        assertSame(q, result);
    }

    @Test
    public void testSetByte_MapPath_NotFound() throws Exception {
        NamedQuery q = createManyParamQuery();
        assertThrows(IllegalArgumentException.class, () -> q.setByte("unknown", (byte) 1));
    }

    @Test
    public void testSetByte_LoopBranch_ParamNotFound() throws SQLException {
        // paramCount < 5, setByte, param not found → lines 371-372
        assertThrows(IllegalArgumentException.class, () -> namedQuery.setByte("unknown", (byte) 1));
    }

    // --- setByte duplicate-occurrence map-branch tests ---

    @Test
    public void testSetByte_MapBranch_TwoOccurrences() throws SQLException {
        NamedQuery q = createMapBranchQuery(ImmutableList.of("a", "b", "c", "p", "p"));
        q.setByte("p", (byte) 7);
        verify(mockPreparedStatement, times(2)).setByte(anyInt(), eq((byte) 7));
    }

    @Test
    public void testSetByte_MapBranch_ThreeOccurrences() throws SQLException {
        NamedQuery q = createMapBranchQuery(ImmutableList.of("a", "b", "c", "p", "p", "p"));
        q.setByte("p", (byte) 3);
        verify(mockPreparedStatement, times(3)).setByte(anyInt(), eq((byte) 3));
    }

    @Test
    public void testSetByte_MapBranch_FourOccurrences() throws SQLException {
        NamedQuery q = createMapBranchQuery(ImmutableList.of("a", "p", "p", "p", "p"));
        q.setByte("p", (byte) 1);
        verify(mockPreparedStatement, times(4)).setByte(anyInt(), eq((byte) 1));
    }

    @Test
    public void testSetByteObjectNull() throws SQLException {
        String paramName = "param1";
        Byte value = null;

        NamedQuery result = namedQuery.setByte(paramName, value);

        verify(mockPreparedStatement).setNull(1, Types.TINYINT);
        assertSame(namedQuery, result);
    }

    @Test
    public void testSetShortPrimitive() throws SQLException {
        String paramName = "param1";
        short value = 500;

        NamedQuery result = namedQuery.setShort(paramName, value);

        verify(mockPreparedStatement).setShort(1, value);
        assertSame(namedQuery, result);
    }

    @Test
    public void testSetShortObject() throws SQLException {
        String paramName = "param1";
        Short value = Short.valueOf((short) 1000);

        NamedQuery result = namedQuery.setShort(paramName, value);

        verify(mockPreparedStatement).setShort(1, (short) 1000);
        assertSame(namedQuery, result);
    }

    @Test
    public void testSetShort_LoopBranch_ParamNotFound() throws SQLException {
        // paramCount < 5, setShort, param not found → lines 459-460
        assertThrows(IllegalArgumentException.class, () -> namedQuery.setShort("unknown", (short) 1));
    }

    // --- setShort: entire map branch uncovered ---

    @Test
    public void testSetShort_MapBranch_NotFound() throws SQLException {
        NamedQuery q = createMapBranchQuery(ImmutableList.of("a", "b", "c", "d", "e"));
        assertThrows(IllegalArgumentException.class, () -> q.setShort("unknown", (short) 1));
    }

    @Test
    public void testSetShort_MapBranch_OneOccurrence() throws SQLException {
        NamedQuery q = createMapBranchQuery(ImmutableList.of("a", "b", "c", "d", "e"));
        q.setShort("b", (short) 10);
        verify(mockPreparedStatement).setShort(2, (short) 10);
    }

    @Test
    public void testSetShort_MapBranch_TwoOccurrences() throws SQLException {
        NamedQuery q = createMapBranchQuery(ImmutableList.of("a", "b", "c", "p", "p"));
        q.setShort("p", (short) 5);
        verify(mockPreparedStatement, times(2)).setShort(anyInt(), eq((short) 5));
    }

    @Test
    public void testSetShort_MapBranch_ThreeOccurrences() throws SQLException {
        NamedQuery q = createMapBranchQuery(ImmutableList.of("a", "b", "c", "p", "p", "p"));
        q.setShort("p", (short) 2);
        verify(mockPreparedStatement, times(3)).setShort(anyInt(), eq((short) 2));
    }

    @Test
    public void testSetShort_MapBranch_FourOccurrences() throws SQLException {
        NamedQuery q = createMapBranchQuery(ImmutableList.of("a", "p", "p", "p", "p"));
        q.setShort("p", (short) 9);
        verify(mockPreparedStatement, times(4)).setShort(anyInt(), eq((short) 9));
    }

    @Test
    public void testSetShortObjectNull() throws SQLException {
        String paramName = "param1";
        Short value = null;

        NamedQuery result = namedQuery.setShort(paramName, value);

        verify(mockPreparedStatement).setNull(1, Types.SMALLINT);
        assertSame(namedQuery, result);
    }

    @Test
    public void testSetIntPrimitive() throws SQLException {
        String paramName = "param1";
        int value = 10000;

        NamedQuery result = namedQuery.setInt(paramName, value);

        verify(mockPreparedStatement).setInt(1, value);
        assertSame(namedQuery, result);
    }

    @Test
    public void testSetIntObject() throws SQLException {
        String paramName = "param1";
        Integer value = Integer.valueOf(50000);

        NamedQuery result = namedQuery.setInt(paramName, value);

        verify(mockPreparedStatement).setInt(1, 50000);
        assertSame(namedQuery, result);
    }

    @Test
    public void testSetIntChar() throws SQLException {
        String paramName = "param1";
        char value = 'A';

        NamedQuery result = namedQuery.setInt(paramName, value);

        verify(mockPreparedStatement).setInt(1, 'A');
        assertSame(namedQuery, result);
    }

    @Test
    public void testSetIntCharacter() throws SQLException {
        String paramName = "param1";
        Character value = Character.valueOf('B');

        NamedQuery result = namedQuery.setInt(paramName, value);

        verify(mockPreparedStatement).setInt(1, 'B');
        assertSame(namedQuery, result);
    }

    @Test
    public void testSetInt_MapPath() throws Exception {
        NamedQuery q = createManyParamQuery();
        NamedQuery result = q.setInt("d", 42);
        verify(mockPreparedStatement).setInt(4, 42);
        assertSame(q, result);
    }

    @Test
    public void testSetInt_MapPath_NotFound() throws Exception {
        NamedQuery q = createManyParamQuery();
        assertThrows(IllegalArgumentException.class, () -> q.setInt("unknown", 1));
    }

    // Test duplicate parameters in index-by-map path (size==2)
    @Test
    public void testSetInt_MapPath_DuplicateParam() throws Exception {
        // 5 total params, "a" appears twice
        when(mockParsedSql.namedParameters()).thenReturn(ImmutableList.of("a", "b", "c", "d", "a"));
        when(mockParsedSql.parameterCount()).thenReturn(5);
        when(mockParsedSql.originalSql()).thenReturn("SELECT 1");
        NamedQuery q = new NamedQuery(mockPreparedStatement, mockParsedSql);
        q.setInt("a", 99);
        verify(mockPreparedStatement).setInt(1, 99);
        verify(mockPreparedStatement).setInt(5, 99);
    }

    // --- setInt: loop not-found, map 3-occ, 4+-occ ---

    @Test
    public void testSetInt_LoopBranch_ParamNotFound() throws SQLException {
        assertThrows(IllegalArgumentException.class, () -> namedQuery.setInt("unknown", 42));
    }

    @Test
    public void testSetInt_MapBranch_ThreeOccurrences() throws SQLException {
        NamedQuery q = createMapBranchQuery(ImmutableList.of("a", "b", "c", "p", "p", "p"));
        q.setInt("p", 99);
        verify(mockPreparedStatement, times(3)).setInt(anyInt(), eq(99));
    }

    @Test
    public void testSetInt_MapBranch_FourOccurrences() throws SQLException {
        NamedQuery q = createMapBranchQuery(ImmutableList.of("a", "p", "p", "p", "p"));
        q.setInt("p", 77);
        verify(mockPreparedStatement, times(4)).setInt(anyInt(), eq(77));
    }

    @Test
    public void testSetIntObjectNull() throws SQLException {
        String paramName = "param1";
        Integer value = null;

        NamedQuery result = namedQuery.setInt(paramName, value);

        verify(mockPreparedStatement).setNull(1, Types.INTEGER);
        assertSame(namedQuery, result);
    }

    @Test
    public void testSetIntCharacterNull() throws SQLException {
        String paramName = "param1";
        Character value = null;

        NamedQuery result = namedQuery.setInt(paramName, value);

        verify(mockPreparedStatement).setNull(1, Types.INTEGER);
        assertSame(namedQuery, result);
    }

    @Test
    public void testSetLongPrimitive() throws SQLException {
        String paramName = "param1";
        long value = 1000000L;

        NamedQuery result = namedQuery.setLong(paramName, value);

        verify(mockPreparedStatement).setLong(1, value);
        assertSame(namedQuery, result);
    }

    @Test
    public void testSetLongObject() throws SQLException {
        String paramName = "param1";
        Long value = Long.valueOf(5000000L);

        NamedQuery result = namedQuery.setLong(paramName, value);

        verify(mockPreparedStatement).setLong(1, 5000000L);
        assertSame(namedQuery, result);
    }

    @Test
    public void testSetLongBigInteger() throws SQLException {
        String paramName = "param1";
        BigInteger value = new BigInteger("9223372036854775807");

        NamedQuery result = namedQuery.setLong(paramName, value);

        verify(mockPreparedStatement).setLong(1, value.longValueExact());
        assertSame(namedQuery, result);
    }

    @Test
    public void testSetLong_MapPath() throws Exception {
        NamedQuery q = createManyParamQuery();
        NamedQuery result = q.setLong("e", 100L);
        verify(mockPreparedStatement).setLong(5, 100L);
        assertSame(q, result);
    }

    // --- setLong: loop not-found + all map missing paths ---

    @Test
    public void testSetLong_LoopBranch_ParamNotFound() throws SQLException {
        assertThrows(IllegalArgumentException.class, () -> namedQuery.setLong("unknown", 100L));
    }

    @Test
    public void testSetLong_MapBranch_NotFound() throws SQLException {
        NamedQuery q = createMapBranchQuery(ImmutableList.of("a", "b", "c", "d", "e"));
        assertThrows(IllegalArgumentException.class, () -> q.setLong("unknown", 1L));
    }

    @Test
    public void testSetLong_MapBranch_TwoOccurrences() throws SQLException {
        NamedQuery q = createMapBranchQuery(ImmutableList.of("a", "b", "c", "p", "p"));
        q.setLong("p", 50L);
        verify(mockPreparedStatement, times(2)).setLong(anyInt(), eq(50L));
    }

    @Test
    public void testSetLong_MapBranch_ThreeOccurrences() throws SQLException {
        NamedQuery q = createMapBranchQuery(ImmutableList.of("a", "b", "c", "p", "p", "p"));
        q.setLong("p", 30L);
        verify(mockPreparedStatement, times(3)).setLong(anyInt(), eq(30L));
    }

    @Test
    public void testSetLong_MapBranch_FourOccurrences() throws SQLException {
        NamedQuery q = createMapBranchQuery(ImmutableList.of("a", "p", "p", "p", "p"));
        q.setLong("p", 8L);
        verify(mockPreparedStatement, times(4)).setLong(anyInt(), eq(8L));
    }

    @Test
    public void testSetLongObjectNull() throws SQLException {
        String paramName = "param1";
        Long value = null;

        NamedQuery result = namedQuery.setLong(paramName, value);

        verify(mockPreparedStatement).setNull(1, Types.BIGINT);
        assertSame(namedQuery, result);
    }

    @Test
    public void testSetLongBigIntegerNull() throws SQLException {
        String paramName = "param1";
        BigInteger value = null;

        NamedQuery result = namedQuery.setLong(paramName, value);

        verify(mockPreparedStatement).setNull(1, Types.BIGINT);
        assertSame(namedQuery, result);
    }

    @Test
    public void testSetLongBigIntegerOverflowClosesStatement() throws SQLException {
        // BigInteger > Long.MAX_VALUE causes ArithmeticException from longValueExact().
        // The statement must be closed so the connection is not leaked.
        BigInteger tooLarge = new BigInteger("18446744073709551616"); // 2^64

        assertThrows(ArithmeticException.class, () -> namedQuery.setLong("param1", tooLarge));

        verify(mockPreparedStatement).close();
    }

    @Test
    public void testSetLongBigIntegerPreservesOverflowWhenCloseHandlerThrows() {
        final RuntimeException closeFailure = new RuntimeException("close handler failed");
        namedQuery.onClose(() -> {
            throw closeFailure;
        });

        final ArithmeticException thrown = assertThrows(ArithmeticException.class,
                () -> namedQuery.setLong("param1", BigInteger.valueOf(Long.MAX_VALUE).add(BigInteger.ONE)));

        assertEquals(1, thrown.getSuppressed().length);
        assertSame(closeFailure, thrown.getSuppressed()[0]);
    }

    // A missing named parameter closes the statement exactly once before conversion or binding.
    @Test
    public void testSetLongBigIntegerUnknownParamClosesOnce() throws SQLException {
        assertThrows(IllegalArgumentException.class, () -> namedQuery.setLong("nonExistent", BigInteger.ONE));

        verify(mockPreparedStatement, times(1)).close();
        verify(mockPreparedStatement, never()).setLong(anyInt(), anyLong());
    }

    @Test
    public void testSetFloatPrimitive() throws SQLException {
        String paramName = "param1";
        float value = 123.45f;

        NamedQuery result = namedQuery.setFloat(paramName, value);

        verify(mockPreparedStatement).setFloat(1, value);
        assertSame(namedQuery, result);
    }

    @Test
    public void testSetFloatObject() throws SQLException {
        String paramName = "param1";
        Float value = Float.valueOf(543.21f);

        NamedQuery result = namedQuery.setFloat(paramName, value);

        verify(mockPreparedStatement).setFloat(1, 543.21f);
        assertSame(namedQuery, result);
    }

    @Test
    public void testSetFloat_MapPath() throws Exception {
        NamedQuery q = createManyParamQuery();
        NamedQuery result = q.setFloat("a", 1.5f);
        verify(mockPreparedStatement).setFloat(1, 1.5f);
        assertSame(q, result);
    }

    // --- setFloat: loop not-found + all map missing paths ---

    @Test
    public void testSetFloat_LoopBranch_ParamNotFound() throws SQLException {
        assertThrows(IllegalArgumentException.class, () -> namedQuery.setFloat("unknown", 1.0f));
    }

    @Test
    public void testSetFloat_MapBranch_NotFound() throws SQLException {
        NamedQuery q = createMapBranchQuery(ImmutableList.of("a", "b", "c", "d", "e"));
        assertThrows(IllegalArgumentException.class, () -> q.setFloat("unknown", 1.0f));
    }

    @Test
    public void testSetFloat_MapBranch_TwoOccurrences() throws SQLException {
        NamedQuery q = createMapBranchQuery(ImmutableList.of("a", "b", "c", "p", "p"));
        q.setFloat("p", 2.5f);
        verify(mockPreparedStatement, times(2)).setFloat(anyInt(), eq(2.5f));
    }

    @Test
    public void testSetFloat_MapBranch_ThreeOccurrences() throws SQLException {
        NamedQuery q = createMapBranchQuery(ImmutableList.of("a", "b", "c", "p", "p", "p"));
        q.setFloat("p", 1.1f);
        verify(mockPreparedStatement, times(3)).setFloat(anyInt(), eq(1.1f));
    }

    @Test
    public void testSetFloat_MapBranch_FourOccurrences() throws SQLException {
        NamedQuery q = createMapBranchQuery(ImmutableList.of("a", "p", "p", "p", "p"));
        q.setFloat("p", 0.5f);
        verify(mockPreparedStatement, times(4)).setFloat(anyInt(), eq(0.5f));
    }

    @Test
    public void testSetFloatObjectNull() throws SQLException {
        String paramName = "param1";
        Float value = null;

        NamedQuery result = namedQuery.setFloat(paramName, value);

        // Per JDBC spec, Java float maps to SQL REAL (Types.FLOAT is the alias for Types.DOUBLE).
        verify(mockPreparedStatement).setNull(1, Types.REAL);
        assertSame(namedQuery, result);
    }

    @Test
    public void testSetDoublePrimitive() throws SQLException {
        String paramName = "param1";
        double value = 123.456789;

        NamedQuery result = namedQuery.setDouble(paramName, value);

        verify(mockPreparedStatement).setDouble(1, value);
        assertSame(namedQuery, result);
    }

    @Test
    public void testSetDoubleObject() throws SQLException {
        String paramName = "param1";
        Double value = Double.valueOf(987.654321);

        NamedQuery result = namedQuery.setDouble(paramName, value);

        verify(mockPreparedStatement).setDouble(1, 987.654321);
        assertSame(namedQuery, result);
    }

    @Test
    public void testSetDouble_MapPath() throws Exception {
        NamedQuery q = createManyParamQuery();
        NamedQuery result = q.setDouble("b", 3.14);
        verify(mockPreparedStatement).setDouble(2, 3.14);
        assertSame(q, result);
    }

    // --- setDouble: loop not-found + all map missing paths ---

    @Test
    public void testSetDouble_LoopBranch_ParamNotFound() throws SQLException {
        assertThrows(IllegalArgumentException.class, () -> namedQuery.setDouble("unknown", 3.14));
    }

    @Test
    public void testSetDouble_MapBranch_NotFound() throws SQLException {
        NamedQuery q = createMapBranchQuery(ImmutableList.of("a", "b", "c", "d", "e"));
        assertThrows(IllegalArgumentException.class, () -> q.setDouble("unknown", 3.14));
    }

    @Test
    public void testSetDouble_MapBranch_TwoOccurrences() throws SQLException {
        NamedQuery q = createMapBranchQuery(ImmutableList.of("a", "b", "c", "p", "p"));
        q.setDouble("p", 2.71);
        verify(mockPreparedStatement, times(2)).setDouble(anyInt(), eq(2.71));
    }

    @Test
    public void testSetDouble_MapBranch_ThreeOccurrences() throws SQLException {
        NamedQuery q = createMapBranchQuery(ImmutableList.of("a", "b", "c", "p", "p", "p"));
        q.setDouble("p", 1.41);
        verify(mockPreparedStatement, times(3)).setDouble(anyInt(), eq(1.41));
    }

    @Test
    public void testSetDouble_MapBranch_FourOccurrences() throws SQLException {
        NamedQuery q = createMapBranchQuery(ImmutableList.of("a", "p", "p", "p", "p"));
        q.setDouble("p", 0.1);
        verify(mockPreparedStatement, times(4)).setDouble(anyInt(), eq(0.1));
    }

    @Test
    public void testSetDoubleObjectNull() throws SQLException {
        String paramName = "param1";
        Double value = null;

        NamedQuery result = namedQuery.setDouble(paramName, value);

        verify(mockPreparedStatement).setNull(1, Types.DOUBLE);
        assertSame(namedQuery, result);
    }

    @Test
    public void testSetBigDecimal() throws SQLException {
        String paramName = "param1";
        BigDecimal value = new BigDecimal("123.45");

        NamedQuery result = namedQuery.setBigDecimal(paramName, value);

        verify(mockPreparedStatement).setBigDecimal(1, value);
        assertSame(namedQuery, result);
    }

    @Test
    public void testSetBigDecimalBigInteger() throws SQLException {
        String paramName = "param1";
        BigInteger value = new BigInteger("12345678901234567890");

        NamedQuery result = namedQuery.setBigDecimal(paramName, value);

        verify(mockPreparedStatement).setBigDecimal(1, new BigDecimal(value));
        assertSame(namedQuery, result);
    }

    // --- setBigDecimal: all paths uncovered ---

    @Test
    public void testSetBigDecimal_LoopBranch_ParamNotFound() throws SQLException {
        assertThrows(IllegalArgumentException.class, () -> namedQuery.setBigDecimal("unknown", BigDecimal.ONE));
    }

    @Test
    public void testSetBigDecimal_MapBranch_NotFound() throws SQLException {
        NamedQuery q = createMapBranchQuery(ImmutableList.of("a", "b", "c", "d", "e"));
        assertThrows(IllegalArgumentException.class, () -> q.setBigDecimal("unknown", BigDecimal.ONE));
    }

    @Test
    public void testSetBigDecimal_MapBranch_OneOccurrence() throws SQLException {
        NamedQuery q = createMapBranchQuery(ImmutableList.of("a", "b", "c", "d", "e"));
        q.setBigDecimal("a", BigDecimal.TEN);
        verify(mockPreparedStatement).setBigDecimal(1, BigDecimal.TEN);
    }

    @Test
    public void testSetBigDecimal_MapBranch_TwoOccurrences() throws SQLException {
        NamedQuery q = createMapBranchQuery(ImmutableList.of("a", "b", "c", "p", "p"));
        q.setBigDecimal("p", BigDecimal.valueOf(5));
        verify(mockPreparedStatement, times(2)).setBigDecimal(anyInt(), eq(BigDecimal.valueOf(5)));
    }

    @Test
    public void testSetBigDecimal_MapBranch_ThreeOccurrences() throws SQLException {
        NamedQuery q = createMapBranchQuery(ImmutableList.of("a", "b", "c", "p", "p", "p"));
        q.setBigDecimal("p", BigDecimal.valueOf(3));
        verify(mockPreparedStatement, times(3)).setBigDecimal(anyInt(), eq(BigDecimal.valueOf(3)));
    }

    @Test
    public void testSetBigDecimal_MapBranch_FourOccurrences() throws SQLException {
        NamedQuery q = createMapBranchQuery(ImmutableList.of("a", "p", "p", "p", "p"));
        q.setBigDecimal("p", BigDecimal.valueOf(9));
        verify(mockPreparedStatement, times(4)).setBigDecimal(anyInt(), eq(BigDecimal.valueOf(9)));
    }

    @Test
    public void testSetBigDecimalBigIntegerNull() throws SQLException {
        String paramName = "param1";
        BigInteger value = null;

        NamedQuery result = namedQuery.setBigDecimal(paramName, value);

        verify(mockPreparedStatement).setNull(1, Types.DECIMAL);
        assertSame(namedQuery, result);
    }

    @Test
    public void testSetBigDecimal_BigIntegerNull_DelegatesToSetNull() throws SQLException {
        namedQuery.setBigDecimal("param1", (BigInteger) null);
        verify(mockPreparedStatement).setNull(1, Types.DECIMAL);
    }

    @Test
    public void testSetBigIntegerAsString() throws SQLException {
        String paramName = "param1";
        BigInteger value = new BigInteger("99999999999999999999");

        NamedQuery result = namedQuery.setBigIntegerAsString(paramName, value);

        verify(mockPreparedStatement).setString(1, value.toString(10));
        assertSame(namedQuery, result);
    }

    @Test
    public void testSetString_DuplicateParameterName() throws SQLException {
        when(mockParsedSql.namedParameters()).thenReturn(ImmutableList.of("param1", "param1"));
        when(mockParsedSql.parameterCount()).thenReturn(2);
        NamedQuery duplicateParamQuery = new NamedQuery(mockPreparedStatement, mockParsedSql);

        NamedQuery result = duplicateParamQuery.setString("param1", "value");

        assertSame(duplicateParamQuery, result);
        verify(mockPreparedStatement).setString(1, "value");
        verify(mockPreparedStatement).setString(2, "value");
    }

    @Test
    public void testSetString() throws SQLException {
        String paramName = "param1";
        String value = "test string";

        NamedQuery result = namedQuery.setString(paramName, value);

        verify(mockPreparedStatement).setString(1, value);
        assertSame(namedQuery, result);
    }

    @Test
    public void testSetStringCharSequence() throws SQLException {
        String paramName = "param1";
        CharSequence value = new StringBuilder("test sequence");

        NamedQuery result = namedQuery.setString(paramName, value);

        verify(mockPreparedStatement).setString(1, "test sequence");
        assertSame(namedQuery, result);
    }

    @Test
    public void testSetStringChar() throws SQLException {
        String paramName = "param1";
        char value = 'X';

        NamedQuery result = namedQuery.setString(paramName, value);

        verify(mockPreparedStatement).setString(1, "X");
        assertSame(namedQuery, result);
    }

    @Test
    public void testSetStringCharacter() throws SQLException {
        String paramName = "param1";
        Character value = Character.valueOf('Y');

        NamedQuery result = namedQuery.setString(paramName, value);

        verify(mockPreparedStatement).setString(1, "Y");
        assertSame(namedQuery, result);
    }

    @Test
    public void testSetStringBigInteger() throws SQLException {
        String paramName = "param1";
        BigInteger value = new BigInteger("123456789012345");

        NamedQuery result = namedQuery.setString(paramName, value);

        verify(mockPreparedStatement).setString(1, value.toString(10));
        assertSame(namedQuery, result);
    }

    @Test
    public void testSetString_MapPath() throws Exception {
        NamedQuery q = createManyParamQuery();
        NamedQuery result = q.setString("c", "hello");
        verify(mockPreparedStatement).setString(3, "hello");
        assertSame(q, result);
    }

    @Test
    public void testSetString_MapPath_NotFound() throws Exception {
        NamedQuery q = createManyParamQuery();
        assertThrows(IllegalArgumentException.class, () -> q.setString("unknown", "val"));
    }

    // --- setString: map 2-occ, 3-occ, 4+-occ ---

    @Test
    public void testSetString_MapBranch_TwoOccurrences() throws SQLException {
        NamedQuery q = createMapBranchQuery(ImmutableList.of("a", "b", "c", "p", "p"));
        q.setString("p", "hello");
        verify(mockPreparedStatement, times(2)).setString(anyInt(), eq("hello"));
    }

    @Test
    public void testSetString_MapBranch_ThreeOccurrences() throws SQLException {
        NamedQuery q = createMapBranchQuery(ImmutableList.of("a", "b", "c", "p", "p", "p"));
        q.setString("p", "world");
        verify(mockPreparedStatement, times(3)).setString(anyInt(), eq("world"));
    }

    @Test
    public void testSetString_MapBranch_FourOccurrences() throws SQLException {
        NamedQuery q = createMapBranchQuery(ImmutableList.of("a", "p", "p", "p", "p"));
        q.setString("p", "four");
        verify(mockPreparedStatement, times(4)).setString(anyInt(), eq("four"));
    }

    @Test
    public void testSetStringCharSequenceNull() throws SQLException {
        String paramName = "param1";
        CharSequence value = null;

        NamedQuery result = namedQuery.setString(paramName, value);

        verify(mockPreparedStatement).setString(1, null);
        assertSame(namedQuery, result);
    }

    @Test
    public void testSetStringCharacterNull() throws SQLException {
        String paramName = "param1";
        Character value = null;

        NamedQuery result = namedQuery.setString(paramName, value);

        verify(mockPreparedStatement).setString(1, null);
        assertSame(namedQuery, result);
    }

    @Test
    public void testSetStringBigIntegerNull() throws SQLException {
        String paramName = "param1";
        BigInteger value = null;

        NamedQuery result = namedQuery.setString(paramName, value);

        verify(mockPreparedStatement).setNull(1, Types.VARCHAR);
        assertSame(namedQuery, result);
    }

    @Test
    public void testSetNString() throws SQLException {
        String paramName = "param1";
        String value = "unicode string";

        NamedQuery result = namedQuery.setNString(paramName, value);

        verify(mockPreparedStatement).setNString(1, value);
        assertSame(namedQuery, result);
    }

    @Test
    public void testSetNStringCharSequence() throws SQLException {
        String paramName = "param1";
        CharSequence value = new StringBuilder("unicode sequence");

        NamedQuery result = namedQuery.setNString(paramName, value);

        verify(mockPreparedStatement).setNString(1, value.toString());
        assertSame(namedQuery, result);
    }

    // --- setNString(String): all paths ---

    @Test
    public void testSetNString_LoopBranch_ParamNotFound() throws SQLException {
        assertThrows(IllegalArgumentException.class, () -> namedQuery.setNString("unknown", "val"));
    }

    @Test
    public void testSetNString_MapBranch_NotFound() throws SQLException {
        NamedQuery q = createMapBranchQuery(ImmutableList.of("a", "b", "c", "d", "e"));
        assertThrows(IllegalArgumentException.class, () -> q.setNString("unknown", "val"));
    }

    @Test
    public void testSetNString_MapBranch_OneOccurrence() throws SQLException {
        NamedQuery q = createMapBranchQuery(ImmutableList.of("a", "b", "c", "d", "e"));
        q.setNString("a", "nstr");
        verify(mockPreparedStatement).setNString(1, "nstr");
    }

    @Test
    public void testSetNString_MapBranch_TwoOccurrences() throws SQLException {
        NamedQuery q = createMapBranchQuery(ImmutableList.of("a", "b", "c", "p", "p"));
        q.setNString("p", "two");
        verify(mockPreparedStatement, times(2)).setNString(anyInt(), eq("two"));
    }

    @Test
    public void testSetNString_MapBranch_ThreeOccurrences() throws SQLException {
        NamedQuery q = createMapBranchQuery(ImmutableList.of("a", "b", "c", "p", "p", "p"));
        q.setNString("p", "three");
        verify(mockPreparedStatement, times(3)).setNString(anyInt(), eq("three"));
    }

    @Test
    public void testSetNString_MapBranch_FourOccurrences() throws SQLException {
        NamedQuery q = createMapBranchQuery(ImmutableList.of("a", "p", "p", "p", "p"));
        q.setNString("p", "four");
        verify(mockPreparedStatement, times(4)).setNString(anyInt(), eq("four"));
    }

    // --- setNString(CharSequence): all paths ---

    @Test
    public void testSetNStringCharSeq_LoopBranch_ParamNotFound() throws SQLException {
        assertThrows(IllegalArgumentException.class, () -> namedQuery.setNString("unknown", new StringBuilder("val")));
    }

    @Test
    public void testSetNStringCharSeq_MapBranch_NotFound() throws SQLException {
        NamedQuery q = createMapBranchQuery(ImmutableList.of("a", "b", "c", "d", "e"));
        assertThrows(IllegalArgumentException.class, () -> q.setNString("unknown", new StringBuilder("val")));
    }

    @Test
    public void testSetNStringCharSeq_MapBranch_OneOccurrence() throws SQLException {
        NamedQuery q = createMapBranchQuery(ImmutableList.of("a", "b", "c", "d", "e"));
        q.setNString("a", new StringBuilder("hi"));
        verify(mockPreparedStatement).setNString(1, "hi");
    }

    @Test
    public void testSetNStringCharSeq_MapBranch_TwoOccurrences() throws SQLException {
        NamedQuery q = createMapBranchQuery(ImmutableList.of("a", "b", "c", "p", "p"));
        q.setNString("p", new StringBuilder("two"));
        verify(mockPreparedStatement, times(2)).setNString(anyInt(), eq("two"));
    }

    @Test
    public void testSetNStringCharSeq_MapBranch_ThreeOccurrences() throws SQLException {
        NamedQuery q = createMapBranchQuery(ImmutableList.of("a", "b", "c", "p", "p", "p"));
        q.setNString("p", new StringBuilder("three"));
        verify(mockPreparedStatement, times(3)).setNString(anyInt(), eq("three"));
    }

    @Test
    public void testSetNStringCharSeq_MapBranch_FourOccurrences() throws SQLException {
        NamedQuery q = createMapBranchQuery(ImmutableList.of("a", "p", "p", "p", "p"));
        q.setNString("p", new StringBuilder("four"));
        verify(mockPreparedStatement, times(4)).setNString(anyInt(), eq("four"));
    }

    @Test
    public void testSetBytes() throws SQLException {
        String paramName = "param1";
        byte[] value = new byte[] { 1, 2, 3, 4, 5 };

        NamedQuery result = namedQuery.setBytes(paramName, value);

        verify(mockPreparedStatement).setBytes(1, value);
        assertSame(namedQuery, result);
    }

    // --- setBytes: all paths ---

    @Test
    public void testSetBytes_LoopBranch_ParamNotFound() throws SQLException {
        assertThrows(IllegalArgumentException.class, () -> namedQuery.setBytes("unknown", new byte[] { 1, 2 }));
    }

    @Test
    public void testSetBytes_MapBranch_NotFound() throws SQLException {
        NamedQuery q = createMapBranchQuery(ImmutableList.of("a", "b", "c", "d", "e"));
        assertThrows(IllegalArgumentException.class, () -> q.setBytes("unknown", new byte[] { 1 }));
    }

    @Test
    public void testSetBytes_MapBranch_OneOccurrence() throws SQLException {
        byte[] data = new byte[] { 1, 2, 3 };
        NamedQuery q = createMapBranchQuery(ImmutableList.of("a", "b", "c", "d", "e"));
        q.setBytes("d", data);
        verify(mockPreparedStatement).setBytes(4, data);
    }

    @Test
    public void testSetBytes_MapBranch_TwoOccurrences() throws SQLException {
        byte[] data = new byte[] { 5 };
        NamedQuery q = createMapBranchQuery(ImmutableList.of("a", "b", "c", "p", "p"));
        q.setBytes("p", data);
        verify(mockPreparedStatement, times(2)).setBytes(anyInt(), eq(data));
    }

    @Test
    public void testSetBytes_MapBranch_ThreeOccurrences() throws SQLException {
        byte[] data = new byte[] { 7 };
        NamedQuery q = createMapBranchQuery(ImmutableList.of("a", "b", "c", "p", "p", "p"));
        q.setBytes("p", data);
        verify(mockPreparedStatement, times(3)).setBytes(anyInt(), eq(data));
    }

    @Test
    public void testSetBytes_MapBranch_FourOccurrences() throws SQLException {
        byte[] data = new byte[] { 9 };
        NamedQuery q = createMapBranchQuery(ImmutableList.of("a", "p", "p", "p", "p"));
        q.setBytes("p", data);
        verify(mockPreparedStatement, times(4)).setBytes(anyInt(), eq(data));
    }
}
