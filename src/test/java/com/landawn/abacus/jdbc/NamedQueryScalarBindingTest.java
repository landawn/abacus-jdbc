package com.landawn.abacus.jdbc;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.sql.SQLException;
import java.sql.Types;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import com.landawn.abacus.util.ImmutableList;

class NamedQueryScalarBindingTest extends NamedQueryBindingTestSupport {

    @Test
    void testSetBooleanPrimitive() throws SQLException {
        String paramName = "param1";
        boolean value = true;

        NamedQuery result = namedQuery.setBoolean(paramName, value);

        verify(mockPreparedStatement).setBoolean(1, value);
        assertSame(namedQuery, result);
    }

    @Test
    void testSetBooleanObject() throws SQLException {
        String paramName = "param1";
        Boolean value = Boolean.FALSE;

        NamedQuery result = namedQuery.setBoolean(paramName, value);

        verify(mockPreparedStatement).setBoolean(1, false);
        assertSame(namedQuery, result);
    }

    @ParameterizedTest(name = "{0}")
    @EnumSource(ParameterLayout.class)
    void setBoolean_matchesNamedParameterPositions(final ParameterLayout layout) throws SQLException {
        assertNamedBinding(layout, (query, parameter) -> query.setBoolean(parameter, true), (statement, index) -> statement.setBoolean(index, true));
    }

    @Test
    void testSetBooleanObjectNull() throws SQLException {
        String paramName = "param1";
        Boolean value = null;

        NamedQuery result = namedQuery.setBoolean(paramName, value);

        verify(mockPreparedStatement).setNull(1, Types.BOOLEAN);
        assertSame(namedQuery, result);
    }

    @Test
    void testSetBytePrimitive() throws SQLException {
        String paramName = "param1";
        byte value = 100;

        NamedQuery result = namedQuery.setByte(paramName, value);

        verify(mockPreparedStatement).setByte(1, value);
        assertSame(namedQuery, result);
    }

    @Test
    void testSetByteObject() throws SQLException {
        String paramName = "param1";
        Byte value = Byte.valueOf((byte) 50);

        NamedQuery result = namedQuery.setByte(paramName, value);

        verify(mockPreparedStatement).setByte(1, (byte) 50);
        assertSame(namedQuery, result);
    }

    @ParameterizedTest(name = "{0}")
    @EnumSource(ParameterLayout.class)
    void setByte_matchesNamedParameterPositions(final ParameterLayout layout) throws SQLException {
        assertNamedBinding(layout, (query, parameter) -> query.setByte(parameter, (byte) 7), (statement, index) -> statement.setByte(index, (byte) 7));
    }

    @Test
    void testSetByteObjectNull() throws SQLException {
        String paramName = "param1";
        Byte value = null;

        NamedQuery result = namedQuery.setByte(paramName, value);

        verify(mockPreparedStatement).setNull(1, Types.TINYINT);
        assertSame(namedQuery, result);
    }

    @Test
    void testSetShortPrimitive() throws SQLException {
        String paramName = "param1";
        short value = 500;

        NamedQuery result = namedQuery.setShort(paramName, value);

        verify(mockPreparedStatement).setShort(1, value);
        assertSame(namedQuery, result);
    }

    @Test
    void testSetShortObject() throws SQLException {
        String paramName = "param1";
        Short value = Short.valueOf((short) 1000);

        NamedQuery result = namedQuery.setShort(paramName, value);

        verify(mockPreparedStatement).setShort(1, (short) 1000);
        assertSame(namedQuery, result);
    }

    @ParameterizedTest(name = "{0}")
    @EnumSource(ParameterLayout.class)
    void setShort_matchesNamedParameterPositions(final ParameterLayout layout) throws SQLException {
        assertNamedBinding(layout, (query, parameter) -> query.setShort(parameter, (short) 10), (statement, index) -> statement.setShort(index, (short) 10));
    }

    @Test
    void testSetShortObjectNull() throws SQLException {
        String paramName = "param1";
        Short value = null;

        NamedQuery result = namedQuery.setShort(paramName, value);

        verify(mockPreparedStatement).setNull(1, Types.SMALLINT);
        assertSame(namedQuery, result);
    }

    @Test
    void testSetIntPrimitive() throws SQLException {
        String paramName = "param1";
        int value = 10000;

        NamedQuery result = namedQuery.setInt(paramName, value);

        verify(mockPreparedStatement).setInt(1, value);
        assertSame(namedQuery, result);
    }

    @Test
    void testSetIntObject() throws SQLException {
        String paramName = "param1";
        Integer value = Integer.valueOf(50000);

        NamedQuery result = namedQuery.setInt(paramName, value);

        verify(mockPreparedStatement).setInt(1, 50000);
        assertSame(namedQuery, result);
    }

    @Test
    void testSetIntChar() throws SQLException {
        String paramName = "param1";
        char value = 'A';

        NamedQuery result = namedQuery.setInt(paramName, value);

        verify(mockPreparedStatement).setInt(1, 'A');
        assertSame(namedQuery, result);
    }

    @Test
    void testSetIntCharacter() throws SQLException {
        String paramName = "param1";
        Character value = Character.valueOf('B');

        NamedQuery result = namedQuery.setInt(paramName, value);

        verify(mockPreparedStatement).setInt(1, 'B');
        assertSame(namedQuery, result);
    }

    @ParameterizedTest(name = "{0}")
    @EnumSource(ParameterLayout.class)
    void setInt_matchesNamedParameterPositions(final ParameterLayout layout) throws SQLException {
        assertNamedBinding(layout, (query, parameter) -> query.setInt(parameter, 42), (statement, index) -> statement.setInt(index, 42));
    }

    @Test
    void testSetIntObjectNull() throws SQLException {
        String paramName = "param1";
        Integer value = null;

        NamedQuery result = namedQuery.setInt(paramName, value);

        verify(mockPreparedStatement).setNull(1, Types.INTEGER);
        assertSame(namedQuery, result);
    }

    @Test
    void testSetIntCharacterNull() throws SQLException {
        String paramName = "param1";
        Character value = null;

        NamedQuery result = namedQuery.setInt(paramName, value);

        verify(mockPreparedStatement).setNull(1, Types.INTEGER);
        assertSame(namedQuery, result);
    }

    @Test
    void testSetLongPrimitive() throws SQLException {
        String paramName = "param1";
        long value = 1000000L;

        NamedQuery result = namedQuery.setLong(paramName, value);

        verify(mockPreparedStatement).setLong(1, value);
        assertSame(namedQuery, result);
    }

    @Test
    void testSetLongObject() throws SQLException {
        String paramName = "param1";
        Long value = Long.valueOf(5000000L);

        NamedQuery result = namedQuery.setLong(paramName, value);

        verify(mockPreparedStatement).setLong(1, 5000000L);
        assertSame(namedQuery, result);
    }

    @Test
    void testSetLongBigInteger() throws SQLException {
        String paramName = "param1";
        BigInteger value = new BigInteger("9223372036854775807");

        NamedQuery result = namedQuery.setLong(paramName, value);

        verify(mockPreparedStatement).setLong(1, value.longValueExact());
        assertSame(namedQuery, result);
    }

    @ParameterizedTest(name = "{0}")
    @EnumSource(ParameterLayout.class)
    void setLong_matchesNamedParameterPositions(final ParameterLayout layout) throws SQLException {
        assertNamedBinding(layout, (query, parameter) -> query.setLong(parameter, 100L), (statement, index) -> statement.setLong(index, 100L));
    }

    @Test
    void testSetLongObjectNull() throws SQLException {
        String paramName = "param1";
        Long value = null;

        NamedQuery result = namedQuery.setLong(paramName, value);

        verify(mockPreparedStatement).setNull(1, Types.BIGINT);
        assertSame(namedQuery, result);
    }

    @Test
    void testSetLongBigIntegerNull() throws SQLException {
        String paramName = "param1";
        BigInteger value = null;

        NamedQuery result = namedQuery.setLong(paramName, value);

        verify(mockPreparedStatement).setNull(1, Types.BIGINT);
        assertSame(namedQuery, result);
    }

    @Test
    void testSetLongBigIntegerOverflowClosesStatement() throws SQLException {
        // BigInteger > Long.MAX_VALUE causes ArithmeticException from longValueExact().
        // The statement must be closed so the connection is not leaked.
        BigInteger tooLarge = new BigInteger("18446744073709551616"); // 2^64

        assertThrows(ArithmeticException.class, () -> namedQuery.setLong("param1", tooLarge));

        verify(mockPreparedStatement).close();
    }

    @Test
    void testSetLongBigIntegerPreservesOverflowWhenCloseHandlerThrows() {
        final RuntimeException closeFailure = new RuntimeException("close handler failed");
        namedQuery.onClose(() -> {
            throw closeFailure;
        });

        final ArithmeticException thrown = assertThrows(ArithmeticException.class,
                () -> namedQuery.setLong("param1", BigInteger.valueOf(Long.MAX_VALUE).add(BigInteger.ONE)));

        assertEquals(1, thrown.getSuppressed().length);
        assertSame(closeFailure, thrown.getSuppressed()[0]);
    }

    @Test
    void testSetLongBigIntegerUnknownParamClosesOnce() throws SQLException {
        assertThrows(IllegalArgumentException.class, () -> namedQuery.setLong("nonExistent", BigInteger.ONE));

        verify(mockPreparedStatement, times(1)).close();
        verify(mockPreparedStatement, never()).setLong(anyInt(), anyLong());
    }

    @Test
    void testSetFloatPrimitive() throws SQLException {
        String paramName = "param1";
        float value = 123.45f;

        NamedQuery result = namedQuery.setFloat(paramName, value);

        verify(mockPreparedStatement).setFloat(1, value);
        assertSame(namedQuery, result);
    }

    @Test
    void testSetFloatObject() throws SQLException {
        String paramName = "param1";
        Float value = Float.valueOf(543.21f);

        NamedQuery result = namedQuery.setFloat(paramName, value);

        verify(mockPreparedStatement).setFloat(1, 543.21f);
        assertSame(namedQuery, result);
    }

    @ParameterizedTest(name = "{0}")
    @EnumSource(ParameterLayout.class)
    void setFloat_matchesNamedParameterPositions(final ParameterLayout layout) throws SQLException {
        assertNamedBinding(layout, (query, parameter) -> query.setFloat(parameter, 1.5f), (statement, index) -> statement.setFloat(index, 1.5f));
    }

    @Test
    void testSetFloatObjectNull() throws SQLException {
        String paramName = "param1";
        Float value = null;

        NamedQuery result = namedQuery.setFloat(paramName, value);

        // Per JDBC spec, Java float maps to SQL REAL (Types.FLOAT is the alias for Types.DOUBLE).
        verify(mockPreparedStatement).setNull(1, Types.REAL);
        assertSame(namedQuery, result);
    }

    @Test
    void testSetDoublePrimitive() throws SQLException {
        String paramName = "param1";
        double value = 123.456789;

        NamedQuery result = namedQuery.setDouble(paramName, value);

        verify(mockPreparedStatement).setDouble(1, value);
        assertSame(namedQuery, result);
    }

    @Test
    void testSetDoubleObject() throws SQLException {
        String paramName = "param1";
        Double value = Double.valueOf(987.654321);

        NamedQuery result = namedQuery.setDouble(paramName, value);

        verify(mockPreparedStatement).setDouble(1, 987.654321);
        assertSame(namedQuery, result);
    }

    @ParameterizedTest(name = "{0}")
    @EnumSource(ParameterLayout.class)
    void setDouble_matchesNamedParameterPositions(final ParameterLayout layout) throws SQLException {
        assertNamedBinding(layout, (query, parameter) -> query.setDouble(parameter, 3.14), (statement, index) -> statement.setDouble(index, 3.14));
    }

    @Test
    void testSetDoubleObjectNull() throws SQLException {
        String paramName = "param1";
        Double value = null;

        NamedQuery result = namedQuery.setDouble(paramName, value);

        verify(mockPreparedStatement).setNull(1, Types.DOUBLE);
        assertSame(namedQuery, result);
    }

    @Test
    void testSetBigDecimal() throws SQLException {
        String paramName = "param1";
        BigDecimal value = new BigDecimal("123.45");

        NamedQuery result = namedQuery.setBigDecimal(paramName, value);

        verify(mockPreparedStatement).setBigDecimal(1, value);
        assertSame(namedQuery, result);
    }

    @Test
    void testSetBigDecimalBigInteger() throws SQLException {
        String paramName = "param1";
        BigInteger value = new BigInteger("12345678901234567890");

        NamedQuery result = namedQuery.setBigDecimal(paramName, value);

        verify(mockPreparedStatement).setBigDecimal(1, new BigDecimal(value));
        assertSame(namedQuery, result);
    }

    @ParameterizedTest(name = "{0}")
    @EnumSource(ParameterLayout.class)
    void setBigDecimal_matchesNamedParameterPositions(final ParameterLayout layout) throws SQLException {
        assertNamedBinding(layout, (query, parameter) -> query.setBigDecimal(parameter, BigDecimal.TEN),
                (statement, index) -> statement.setBigDecimal(index, BigDecimal.TEN));
    }

    @Test
    void testSetBigDecimalBigIntegerNull() throws SQLException {
        String paramName = "param1";
        BigInteger value = null;

        NamedQuery result = namedQuery.setBigDecimal(paramName, value);

        verify(mockPreparedStatement).setNull(1, Types.DECIMAL);
        assertSame(namedQuery, result);
    }

    @Test
    void testSetString() throws SQLException {
        String paramName = "param1";
        String value = "test string";

        NamedQuery result = namedQuery.setString(paramName, value);

        verify(mockPreparedStatement).setString(1, value);
        assertSame(namedQuery, result);
    }

    @Test
    void testSetStringCharSequence() throws SQLException {
        String paramName = "param1";
        CharSequence value = new StringBuilder("test sequence");

        NamedQuery result = namedQuery.setString(paramName, value);

        verify(mockPreparedStatement).setString(1, "test sequence");
        assertSame(namedQuery, result);
    }

    @Test
    void testSetStringChar() throws SQLException {
        String paramName = "param1";
        char value = 'X';

        NamedQuery result = namedQuery.setString(paramName, value);

        verify(mockPreparedStatement).setString(1, "X");
        assertSame(namedQuery, result);
    }

    @Test
    void testSetStringCharacter() throws SQLException {
        String paramName = "param1";
        Character value = Character.valueOf('Y');

        NamedQuery result = namedQuery.setString(paramName, value);

        verify(mockPreparedStatement).setString(1, "Y");
        assertSame(namedQuery, result);
    }

    @Test
    void testSetStringBigInteger() throws SQLException {
        String paramName = "param1";
        BigInteger value = new BigInteger("123456789012345");

        NamedQuery result = namedQuery.setString(paramName, value);

        verify(mockPreparedStatement).setString(1, value.toString(10));
        assertSame(namedQuery, result);
    }

    @ParameterizedTest(name = "{0}")
    @EnumSource(ParameterLayout.class)
    void setString_matchesNamedParameterPositions(final ParameterLayout layout) throws SQLException {
        assertNamedBinding(layout, (query, parameter) -> query.setString(parameter, "hello"), (statement, index) -> statement.setString(index, "hello"));
    }

    @Test
    void testSetStringCharSequenceNull() throws SQLException {
        String paramName = "param1";
        CharSequence value = null;

        NamedQuery result = namedQuery.setString(paramName, value);

        verify(mockPreparedStatement).setString(1, null);
        assertSame(namedQuery, result);
    }

    @Test
    void testSetStringCharacterNull() throws SQLException {
        String paramName = "param1";
        Character value = null;

        NamedQuery result = namedQuery.setString(paramName, value);

        verify(mockPreparedStatement).setString(1, null);
        assertSame(namedQuery, result);
    }

    @Test
    void testSetStringBigIntegerNull() throws SQLException {
        String paramName = "param1";
        BigInteger value = null;

        NamedQuery result = namedQuery.setString(paramName, value);

        verify(mockPreparedStatement).setNull(1, Types.VARCHAR);
        assertSame(namedQuery, result);
    }

    @Test
    void testSetNString() throws SQLException {
        String paramName = "param1";
        String value = "unicode string";

        NamedQuery result = namedQuery.setNString(paramName, value);

        verify(mockPreparedStatement).setNString(1, value);
        assertSame(namedQuery, result);
    }

    @Test
    void testSetNStringCharSequence() throws SQLException {
        String paramName = "param1";
        CharSequence value = new StringBuilder("unicode sequence");

        NamedQuery result = namedQuery.setNString(paramName, value);

        verify(mockPreparedStatement).setNString(1, value.toString());
        assertSame(namedQuery, result);
    }

    @ParameterizedTest(name = "{0}")
    @EnumSource(ParameterLayout.class)
    void setNString_matchesNamedParameterPositions(final ParameterLayout layout) throws SQLException {
        assertNamedBinding(layout, (query, parameter) -> query.setNString(parameter, "nstr"), (statement, index) -> statement.setNString(index, "nstr"));
    }

    @ParameterizedTest(name = "{0}")
    @EnumSource(ParameterLayout.class)
    void setNStringCharSequence_matchesNamedParameterPositions(final ParameterLayout layout) throws SQLException {
        assertNamedBinding(layout, (query, parameter) -> query.setNString(parameter, new StringBuilder("hi")),
                (statement, index) -> statement.setNString(index, "hi"));
    }

    @Test
    void testSetBytes() throws SQLException {
        String paramName = "param1";
        byte[] value = new byte[] { 1, 2, 3, 4, 5 };

        NamedQuery result = namedQuery.setBytes(paramName, value);

        verify(mockPreparedStatement).setBytes(1, value);
        assertSame(namedQuery, result);
    }

    @ParameterizedTest(name = "{0}")
    @EnumSource(ParameterLayout.class)
    void setBytes_matchesNamedParameterPositions(final ParameterLayout layout) throws SQLException {
        final byte[] data = new byte[] { 1, 2, 3 };
        assertNamedBinding(layout, (query, parameter) -> query.setBytes(parameter, data), (statement, index) -> statement.setBytes(index, data));
    }

    @Test
    void testSetBigIntegerAsString() throws SQLException {
        String paramName = "param1";
        BigInteger value = new BigInteger("99999999999999999999");

        NamedQuery result = namedQuery.setBigIntegerAsString(paramName, value);

        verify(mockPreparedStatement).setString(1, value.toString(10));
        assertSame(namedQuery, result);
    }

    @Test
    void testSetString_DuplicateParameterName() throws SQLException {
        when(mockParsedSql.namedParameters()).thenReturn(ImmutableList.of("param1", "param1"));
        when(mockParsedSql.parameterCount()).thenReturn(2);
        NamedQuery duplicateParamQuery = new NamedQuery(mockPreparedStatement, mockParsedSql);

        NamedQuery result = duplicateParamQuery.setString("param1", "value");

        assertSame(duplicateParamQuery, result);
        verify(mockPreparedStatement).setString(1, "value");
        verify(mockPreparedStatement).setString(2, "value");
    }
}
