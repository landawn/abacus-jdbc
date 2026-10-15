package com.landawn.abacus.jdbc;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.io.Reader;
import java.io.StringReader;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.net.URL;
import java.sql.Blob;
import java.sql.CallableStatement;
import java.sql.Clob;
import java.sql.Connection;
import java.sql.Date;
import java.sql.JDBCType;
import java.sql.NClob;
import java.sql.RowId;
import java.sql.SQLException;
import java.sql.SQLType;
import java.sql.SQLXML;
import java.sql.Time;
import java.sql.Timestamp;
import java.sql.Types;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.time.ZonedDateTime;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.Mockito;

class CallableQueryParameterBindingTest extends CallableQueryTestSupport {

    @ParameterizedTest
    @NullSource
    @ValueSource(strings = { "", "unicode \u03bb" })
    void setNString_charSequence_bindsTextOrSqlNull(final String value) throws SQLException {
        final CharSequence characters = value == null ? null : new StringBuilder(value);

        assertSame(callableQuery, callableQuery.setNString("text", characters));
        verify(callableStatement).setNString("text", value);
    }

    // Tests for setNull by name overloads
    @Test
    public void testSetNull_ByName_SqlType() throws SQLException {
        CallableQuery result = callableQuery.setNull("param", Types.INTEGER);
        assertSame(callableQuery, result);
        verify(callableStatement).setNull("param", Types.INTEGER);
    }

    @Test
    public void testSetNull_ByName_SqlTypeAndTypeName() throws SQLException {
        CallableQuery result = callableQuery.setNull("param", Types.STRUCT, "MY_TYPE");
        assertSame(callableQuery, result);
        verify(callableStatement).setNull("param", Types.STRUCT, "MY_TYPE");
    }

    // Tests for setBoolean by name (primitive)
    @Test
    public void testSetBoolean_ByName_Primitive() throws SQLException {
        CallableQuery result = callableQuery.setBoolean("active", true);
        assertSame(callableQuery, result);
        verify(callableStatement).setBoolean("active", true);
    }

    @Test
    public void testSetBoolean_NullValue() throws SQLException {
        CallableQuery result = callableQuery.setBoolean("active", (Boolean) null);

        assertSame(callableQuery, result);
        verify(callableStatement).setNull("active", Types.BOOLEAN);
    }

    // Tests for setBoolean non-null Boolean wrapper
    @Test
    public void testSetBoolean_ByName_BooleanNonNull() throws SQLException {
        CallableQuery result = callableQuery.setBoolean("active", Boolean.TRUE);
        assertSame(callableQuery, result);
        verify(callableStatement).setBoolean("active", true);
    }

    // Tests for setByte by name
    @Test
    public void testSetByte_ByName_Primitive() throws SQLException {
        CallableQuery result = callableQuery.setByte("code", (byte) 5);
        assertSame(callableQuery, result);
        verify(callableStatement).setByte("code", (byte) 5);
    }

    @Test
    public void testSetByte_ByName_WrapperNull() throws SQLException {
        CallableQuery result = callableQuery.setByte("code", (Byte) null);
        assertSame(callableQuery, result);
        verify(callableStatement).setNull("code", Types.TINYINT);
    }

    @Test
    public void testSetByte_ByName_WrapperNonNull() throws SQLException {
        CallableQuery result = callableQuery.setByte("code", Byte.valueOf((byte) 3));
        assertSame(callableQuery, result);
        verify(callableStatement).setByte("code", (byte) 3);
    }

    // Tests for setShort by name
    @Test
    public void testSetShort_ByName_Primitive() throws SQLException {
        CallableQuery result = callableQuery.setShort("s", (short) 100);
        assertSame(callableQuery, result);
        verify(callableStatement).setShort("s", (short) 100);
    }

    @Test
    public void testSetShort_ByName_WrapperNull() throws SQLException {
        CallableQuery result = callableQuery.setShort("s", (Short) null);
        assertSame(callableQuery, result);
        verify(callableStatement).setNull("s", Types.SMALLINT);
    }

    @Test
    public void testSetShort_ByName_WrapperNonNull() throws SQLException {
        CallableQuery result = callableQuery.setShort("s", (Short) (short) 7);
        assertSame(callableQuery, result);
        verify(callableStatement).setShort("s", (short) 7);
    }

    // Tests for setInt by name
    @Test
    public void testSetInt_ByName_Primitive() throws SQLException {
        CallableQuery result = callableQuery.setInt("count", 42);
        assertSame(callableQuery, result);
        verify(callableStatement).setInt("count", 42);
    }

    @Test
    public void testSetInt_ByName_WrapperNull() throws SQLException {
        CallableQuery result = callableQuery.setInt("count", (Integer) null);
        assertSame(callableQuery, result);
        verify(callableStatement).setNull("count", Types.INTEGER);
    }

    @Test
    public void testSetInt_ByName_WrapperNonNull() throws SQLException {
        CallableQuery result = callableQuery.setInt("count", Integer.valueOf(42));
        assertSame(callableQuery, result);
        verify(callableStatement).setInt("count", 42);
    }

    // Tests for setLong by name
    @Test
    public void testSetLong_ByName_Primitive() throws SQLException {
        CallableQuery result = callableQuery.setLong("id", 1000L);
        assertSame(callableQuery, result);
        verify(callableStatement).setLong("id", 1000L);
    }

    @Test
    public void testSetLong_ByName_WrapperNull() throws SQLException {
        CallableQuery result = callableQuery.setLong("id", (Long) null);
        assertSame(callableQuery, result);
        verify(callableStatement).setNull("id", Types.BIGINT);
    }

    // setLong(String, BigInteger) - non-null path
    @Test
    public void testSetLong_ByName_BigIntegerNonNull() throws SQLException {
        CallableQuery result = callableQuery.setLong("param", new BigInteger("12345"));
        assertSame(callableQuery, result);
        verify(callableStatement).setLong("param", 12345L);
    }

    // setLong(String, BigInteger) - null path
    @Test
    public void testSetLong_ByName_BigIntegerNull() throws SQLException {
        CallableQuery result = callableQuery.setLong("param", (BigInteger) null);
        assertSame(callableQuery, result);
        verify(callableStatement).setNull("param", Types.BIGINT);
    }

    @Test
    public void testSetLong_ByName_WrapperNonNull() throws SQLException {
        CallableQuery result = callableQuery.setLong("id", Long.valueOf(1000L));
        assertSame(callableQuery, result);
        verify(callableStatement).setLong("id", 1000L);
    }

    @Test
    public void testSetLong_ByName_BigIntegerNonNull_Exact() throws SQLException {
        final java.math.BigInteger val = java.math.BigInteger.valueOf(9999L);
        CallableQuery result = callableQuery.setLong("bi", val);
        assertSame(callableQuery, result);
        verify(callableStatement).setLong("bi", 9999L);
    }

    @Test
    public void testSetLongBigIntegerPreservesOverflowWhenCloseHandlerThrows() {
        final RuntimeException closeFailure = new RuntimeException("close handler failed");
        callableQuery.onClose(() -> {
            throw closeFailure;
        });

        final ArithmeticException thrown = assertThrows(ArithmeticException.class,
                () -> callableQuery.setLong("bi", BigInteger.valueOf(Long.MAX_VALUE).add(BigInteger.ONE)));

        assertEquals(1, thrown.getSuppressed().length);
        assertSame(closeFailure, thrown.getSuppressed()[0]);
    }

    // setLong(String, BigInteger) - ArithmeticException closes statement (L436 pc, L437 nc)
    @Test
    public void testSetLong_ByName_BigIntegerTooLarge_ClosesStatement() throws SQLException {
        final java.math.BigInteger huge = new java.math.BigInteger("99999999999999999999");
        assertThrows(ArithmeticException.class, () -> callableQuery.setLong("bi", huge));
        verify(callableStatement).close();
    }

    // Tests for setFloat by name
    @Test
    public void testSetFloat_ByName_Primitive() throws SQLException {
        CallableQuery result = callableQuery.setFloat("f", 1.5f);
        assertSame(callableQuery, result);
        verify(callableStatement).setFloat("f", 1.5f);
    }

    @Test
    public void testSetFloat_ByName_WrapperNull() throws SQLException {
        CallableQuery result = callableQuery.setFloat("f", (Float) null);
        assertSame(callableQuery, result);
        // Per JDBC spec, Java float maps to SQL REAL (Types.FLOAT is the alias for Types.DOUBLE).
        verify(callableStatement).setNull("f", Types.REAL);
    }

    @Test
    public void testSetFloat_ByName_WrapperNonNull() throws SQLException {
        CallableQuery result = callableQuery.setFloat("f", Float.valueOf(2.5f));
        assertSame(callableQuery, result);
        verify(callableStatement).setFloat("f", 2.5f);
    }

    // Tests for setDouble by name
    @Test
    public void testSetDouble_ByName_Primitive() throws SQLException {
        CallableQuery result = callableQuery.setDouble("d", 3.14);
        assertSame(callableQuery, result);
        verify(callableStatement).setDouble("d", 3.14);
    }

    @Test
    public void testSetDouble_ByName_WrapperNull() throws SQLException {
        CallableQuery result = callableQuery.setDouble("d", (Double) null);
        assertSame(callableQuery, result);
        verify(callableStatement).setNull("d", Types.DOUBLE);
    }

    @Test
    public void testSetDouble_ByName_WrapperNonNull() throws SQLException {
        CallableQuery result = callableQuery.setDouble("d", Double.valueOf(3.14));
        assertSame(callableQuery, result);
        verify(callableStatement).setDouble("d", 3.14);
    }

    // Tests for setBigDecimal by name
    @Test
    public void testSetBigDecimal_ByName_NonNull() throws SQLException {
        BigDecimal bd = new BigDecimal("123.45");
        CallableQuery result = callableQuery.setBigDecimal("amount", bd);
        assertSame(callableQuery, result);
        verify(callableStatement).setBigDecimal("amount", bd);
    }

    @Test
    public void testSetBigDecimal_ByName_Null() throws SQLException {
        // setBigDecimal(String, BigDecimal) calls cstmt.setBigDecimal with null directly (no setNull)
        CallableQuery result = callableQuery.setBigDecimal("amount", (BigDecimal) null);
        assertSame(callableQuery, result);
        verify(callableStatement).setBigDecimal("amount", null);
    }

    // setBigDecimal(String, BigInteger) - non-null path
    @Test
    public void testSetBigDecimal_ByName_BigIntegerNonNull() throws SQLException {
        BigInteger val = new BigInteger("9999");
        CallableQuery result = callableQuery.setBigDecimal("param", val);
        assertSame(callableQuery, result);
        verify(callableStatement).setBigDecimal("param", new BigDecimal(val));
    }

    // setBigDecimal(String, BigInteger) - null path
    @Test
    public void testSetBigDecimal_ByName_BigIntegerNull() throws SQLException {
        CallableQuery result = callableQuery.setBigDecimal("param", (BigInteger) null);
        assertSame(callableQuery, result);
        verify(callableStatement).setNull("param", Types.DECIMAL);
    }

    @Test
    public void testSetBigDecimal_ByName_BigIntegerNull2() throws SQLException {
        CallableQuery result = callableQuery.setBigDecimal("x", (java.math.BigInteger) null);
        assertSame(callableQuery, result);
        verify(callableStatement).setNull("x", Types.DECIMAL);
    }

    @Test
    public void testSetBigDecimal_ByName_BigIntegerNonNull2() throws SQLException {
        final java.math.BigInteger val = new java.math.BigInteger("12345");
        CallableQuery result = callableQuery.setBigDecimal("x", val);
        assertSame(callableQuery, result);
        verify(callableStatement).setBigDecimal("x", new java.math.BigDecimal(val));
    }

    // setBigIntegerAsString(String, BigInteger) - delegates to setString
    @Test
    public void testSetBigIntegerAsString_NonNull() throws SQLException {
        BigInteger val = new BigInteger("42");
        CallableQuery result = callableQuery.setBigIntegerAsString("param", val);
        assertSame(callableQuery, result);
        verify(callableStatement).setString("param", "42");
    }

    // setBigIntegerAsString(String, BigInteger) — delegates to setString(String, BigInteger)
    @Test
    public void testSetBigIntegerAsString_ByName_NonNull() throws SQLException {
        BigInteger val = new BigInteger("12345678901234567890");
        CallableQuery result = callableQuery.setBigIntegerAsString("bigStr", val);
        assertSame(callableQuery, result);
        verify(callableStatement).setString("bigStr", val.toString(10));
    }

    @Test
    public void testSetBigIntegerAsString_ByName_Null() throws SQLException {
        CallableQuery result = callableQuery.setBigIntegerAsString("bigStr", null);
        assertSame(callableQuery, result);
        verify(callableStatement).setNull("bigStr", Types.VARCHAR);
    }

    @Test
    public void testSetString_ByName_Char() throws SQLException {
        CallableQuery result = callableQuery.setString("grade", 'A');
        assertSame(callableQuery, result);
        verify(callableStatement).setString("grade", "A");
    }

    // Tests for setString by name
    @Test
    public void testSetString_ByName_NonNull() throws SQLException {
        CallableQuery result = callableQuery.setString("name", "Alice");
        assertSame(callableQuery, result);
        verify(callableStatement).setString("name", "Alice");
    }

    @Test
    public void testSetString_ByName_Null() throws SQLException {
        // setString(String, String) calls cstmt.setString with null directly
        CallableQuery result = callableQuery.setString("name", (String) null);
        assertSame(callableQuery, result);
        verify(callableStatement).setString("name", null);
    }

    @Test
    public void testSetString_ByName_CharSequenceNonNull() throws SQLException {
        CallableQuery result = callableQuery.setString("msg", new StringBuilder("hello"));
        assertSame(callableQuery, result);
        verify(callableStatement).setString("msg", "hello");
    }

    @Test
    public void testSetString_ByName_CharacterNonNull() throws SQLException {
        CallableQuery result = callableQuery.setString("init", Character.valueOf('Z'));
        assertSame(callableQuery, result);
        verify(callableStatement).setString("init", "Z");
    }

    @Test
    public void testSetString_ByName_CharacterNull() throws SQLException {
        CallableQuery result = callableQuery.setString("init", (Character) null);
        assertSame(callableQuery, result);
        verify(callableStatement).setString("init", (String) null);
    }

    @Test
    public void testSetString_ByName_BigIntegerNonNull() throws SQLException {
        final java.math.BigInteger bi = new java.math.BigInteger("999");
        CallableQuery result = callableQuery.setString("val", bi);
        assertSame(callableQuery, result);
        verify(callableStatement).setString("val", "999");
    }

    @Test
    public void testSetString_ByName_BigIntegerNull() throws SQLException {
        CallableQuery result = callableQuery.setString("val", (java.math.BigInteger) null);
        assertSame(callableQuery, result);
        verify(callableStatement).setNull("val", Types.VARCHAR);
    }

    // setString(String, CharSequence) - null branch (L645 pc)
    @Test
    public void testSetString_ByName_CharSequenceNull() throws SQLException {
        CallableQuery result = callableQuery.setString("msg", (CharSequence) null);
        assertSame(callableQuery, result);
        verify(callableStatement).setString("msg", (String) null);
    }

    // setNString(String, String) — not previously tested
    @Test
    public void testSetNString_ByName() throws SQLException {
        CallableQuery result = callableQuery.setNString("nname", "unicode");
        assertSame(callableQuery, result);
        verify(callableStatement).setNString("nname", "unicode");
    }

    // setDate(String, java.util.Date) - non-null util.Date path
    @Test
    public void testSetDate_ByName_UtilDate() throws SQLException {
        java.util.Date date = new java.util.Date(1000L);
        CallableQuery result = callableQuery.setDate("param", date);
        assertSame(callableQuery, result);
        verify(callableStatement).setDate("param", new java.sql.Date(1000L));
    }

    // setDate(String, LocalDate) - non-null path
    @Test
    public void testSetDate_ByName_LocalDate() throws SQLException {
        LocalDate ld = LocalDate.of(2024, 1, 15);
        CallableQuery result = callableQuery.setDate("param", ld);
        assertSame(callableQuery, result);
        verify(callableStatement).setDate("param", java.sql.Date.valueOf(ld));
    }

    // setDate(String, java.util.Date) - java.sql.Date instance branch (L768 pc)
    @Test
    public void testSetDate_ByName_UtilDateAsSqlDate() throws SQLException {
        java.sql.Date sqlDate = new java.sql.Date(System.currentTimeMillis());
        CallableQuery result = callableQuery.setDate("dt", (java.util.Date) sqlDate);
        assertSame(callableQuery, result);
        verify(callableStatement).setDate("dt", sqlDate);
    }

    // Tests for setDate by name
    @Test
    public void testSetDate_ByName_NonNull() throws SQLException {
        Date date = new Date(System.currentTimeMillis());
        CallableQuery result = callableQuery.setDate("dt", date);
        assertSame(callableQuery, result);
        verify(callableStatement).setDate("dt", date);
    }

    @Test
    public void testSetDate_ByName_Null() throws SQLException {
        // setDate(String, Date) calls cstmt.setDate with null directly
        CallableQuery result = callableQuery.setDate("dt", (Date) null);
        assertSame(callableQuery, result);
        verify(callableStatement).setDate("dt", (Date) null);
    }

    // setDate(String, java.util.Date) - null branch (L768 pc)
    @Test
    public void testSetDate_ByName_UtilDateNull() throws SQLException {
        CallableQuery result = callableQuery.setDate("dt", (java.util.Date) null);
        assertSame(callableQuery, result);
        verify(callableStatement).setDate("dt", (java.sql.Date) null);
    }

    // setDate(String, LocalDate) - null branch (L788 pc)
    @Test
    public void testSetDate_ByName_LocalDateNull() throws SQLException {
        CallableQuery result = callableQuery.setDate("dt", (LocalDate) null);
        assertSame(callableQuery, result);
        verify(callableStatement).setDate("dt", (java.sql.Date) null);
    }

    // setTime(String, java.util.Date) - non-null util.Date path
    @Test
    public void testSetTime_ByName_UtilDate() throws SQLException {
        java.util.Date date = new java.util.Date(5000L);
        CallableQuery result = callableQuery.setTime("param", date);
        assertSame(callableQuery, result);
        verify(callableStatement).setTime("param", new java.sql.Time(5000L));
    }

    // setTime(String, LocalTime) - non-null path
    @Test
    public void testSetTime_ByName_LocalTime() throws SQLException {
        LocalTime lt = LocalTime.of(14, 30, 0);
        CallableQuery result = callableQuery.setTime("param", lt);
        assertSame(callableQuery, result);
        verify(callableStatement).setTime("param", java.sql.Time.valueOf(lt));
    }

    // setTime(String, java.util.Date) - java.sql.Time instance branch (L830 pc)
    @Test
    public void testSetTime_ByName_UtilDateAsSqlTime() throws SQLException {
        java.sql.Time sqlTime = new java.sql.Time(System.currentTimeMillis());
        CallableQuery result = callableQuery.setTime("t", (java.util.Date) sqlTime);
        assertSame(callableQuery, result);
        verify(callableStatement).setTime("t", sqlTime);
    }

    // Tests for setTime by name
    @Test
    public void testSetTime_ByName_NonNull() throws SQLException {
        Time time = new Time(System.currentTimeMillis());
        CallableQuery result = callableQuery.setTime("t", time);
        assertSame(callableQuery, result);
        verify(callableStatement).setTime("t", time);
    }

    @Test
    public void testSetTime_ByName_Null() throws SQLException {
        // setTime(String, Time) calls cstmt.setTime with null directly
        CallableQuery result = callableQuery.setTime("t", (Time) null);
        assertSame(callableQuery, result);
        verify(callableStatement).setTime("t", (Time) null);
    }

    // setTime(String, java.util.Date) - null branch (L830 pc)
    @Test
    public void testSetTime_ByName_UtilDateNull() throws SQLException {
        CallableQuery result = callableQuery.setTime("t", (java.util.Date) null);
        assertSame(callableQuery, result);
        verify(callableStatement).setTime("t", (java.sql.Time) null);
    }

    // setTime(String, LocalTime) - null branch (L850 pc)
    @Test
    public void testSetTime_ByName_LocalTimeNull() throws SQLException {
        CallableQuery result = callableQuery.setTime("t", (LocalTime) null);
        assertSame(callableQuery, result);
        verify(callableStatement).setTime("t", (java.sql.Time) null);
    }

    // setTimestamp(String, java.util.Date) - non-null util.Date path
    @Test
    public void testSetTimestamp_ByName_UtilDate() throws SQLException {
        java.util.Date date = new java.util.Date(9000L);
        CallableQuery result = callableQuery.setTimestamp("param", date);
        assertSame(callableQuery, result);
        verify(callableStatement).setTimestamp("param", new java.sql.Timestamp(9000L));
    }

    // setTimestamp(String, LocalDateTime) - non-null path
    @Test
    public void testSetTimestamp_ByName_LocalDateTime() throws SQLException {
        LocalDateTime ldt = LocalDateTime.of(2024, 3, 15, 10, 0, 0);
        CallableQuery result = callableQuery.setTimestamp("param", ldt);
        assertSame(callableQuery, result);
        verify(callableStatement).setTimestamp("param", Timestamp.valueOf(ldt));
    }

    // setTimestamp(String, ZonedDateTime) - non-null path
    @Test
    public void testSetTimestamp_ByName_ZonedDateTime() throws SQLException {
        ZonedDateTime zdt = ZonedDateTime.of(2024, 3, 15, 10, 0, 0, 0, ZoneOffset.UTC);
        CallableQuery result = callableQuery.setTimestamp("param", zdt);
        assertSame(callableQuery, result);
        verify(callableStatement).setTimestamp("param", Timestamp.from(zdt.toInstant()));
    }

    // setTimestamp(String, OffsetDateTime) - non-null path
    @Test
    public void testSetTimestamp_ByName_OffsetDateTime() throws SQLException {
        OffsetDateTime odt = OffsetDateTime.of(2024, 3, 15, 10, 0, 0, 0, ZoneOffset.UTC);
        CallableQuery result = callableQuery.setTimestamp("param", odt);
        assertSame(callableQuery, result);
        verify(callableStatement).setTimestamp("param", Timestamp.from(odt.toInstant()));
    }

    // setTimestamp(String, Instant) - non-null path
    @Test
    public void testSetTimestamp_ByName_Instant() throws SQLException {
        Instant instant = Instant.ofEpochMilli(1000L);
        CallableQuery result = callableQuery.setTimestamp("param", instant);
        assertSame(callableQuery, result);
        verify(callableStatement).setTimestamp("param", Timestamp.from(instant));
    }

    // setTimestamp(String, java.util.Date) - java.sql.Timestamp instance branch (L890 pc)
    @Test
    public void testSetTimestamp_ByName_UtilDateAsSqlTimestamp() throws SQLException {
        java.sql.Timestamp ts = new java.sql.Timestamp(System.currentTimeMillis());
        CallableQuery result = callableQuery.setTimestamp("ts", (java.util.Date) ts);
        assertSame(callableQuery, result);
        verify(callableStatement).setTimestamp("ts", ts);
    }

    // Tests for setTimestamp by name
    @Test
    public void testSetTimestamp_ByName_NonNull() throws SQLException {
        Timestamp ts = new Timestamp(System.currentTimeMillis());
        CallableQuery result = callableQuery.setTimestamp("ts", ts);
        assertSame(callableQuery, result);
        verify(callableStatement).setTimestamp("ts", ts);
    }

    @Test
    public void testSetTimestamp_ByName_Null() throws SQLException {
        // setTimestamp(String, Timestamp) calls cstmt.setTimestamp with null directly
        CallableQuery result = callableQuery.setTimestamp("ts", (Timestamp) null);
        assertSame(callableQuery, result);
        verify(callableStatement).setTimestamp("ts", (Timestamp) null);
    }

    // setTimestamp(String, java.util.Date) - null branch (L890 pc)
    @Test
    public void testSetTimestamp_ByName_UtilDateNull() throws SQLException {
        CallableQuery result = callableQuery.setTimestamp("ts", (java.util.Date) null);
        assertSame(callableQuery, result);
        verify(callableStatement).setTimestamp("ts", (java.sql.Timestamp) null);
    }

    // setTimestamp(String, LocalDateTime) - null branch (L910 pc)
    @Test
    public void testSetTimestamp_ByName_LocalDateTimeNull() throws SQLException {
        CallableQuery result = callableQuery.setTimestamp("ts", (LocalDateTime) null);
        assertSame(callableQuery, result);
        verify(callableStatement).setTimestamp("ts", (java.sql.Timestamp) null);
    }

    // setTimestamp(String, ZonedDateTime) - null branch (L931 pc)
    @Test
    public void testSetTimestamp_ByName_ZonedDateTimeNull() throws SQLException {
        CallableQuery result = callableQuery.setTimestamp("ts", (ZonedDateTime) null);
        assertSame(callableQuery, result);
        verify(callableStatement).setTimestamp("ts", (java.sql.Timestamp) null);
    }

    // setTimestamp(String, OffsetDateTime) - null branch (L952 pc)
    @Test
    public void testSetTimestamp_ByName_OffsetDateTimeNull() throws SQLException {
        CallableQuery result = callableQuery.setTimestamp("ts", (OffsetDateTime) null);
        assertSame(callableQuery, result);
        verify(callableStatement).setTimestamp("ts", (java.sql.Timestamp) null);
    }

    // setTimestamp(String, Instant) - null branch (L973 pc)
    @Test
    public void testSetTimestamp_ByName_InstantNull() throws SQLException {
        CallableQuery result = callableQuery.setTimestamp("ts", (Instant) null);
        assertSame(callableQuery, result);
        verify(callableStatement).setTimestamp("ts", (java.sql.Timestamp) null);
    }

    // Tests for setBytes by name
    @Test
    public void testSetBytes_ByName_NonNull() throws SQLException {
        byte[] data = { 1, 2, 3 };
        CallableQuery result = callableQuery.setBytes("data", data);
        assertSame(callableQuery, result);
        verify(callableStatement).setBytes("data", data);
    }

    @Test
    public void testSetBytes_ByName_Null() throws SQLException {
        // setBytes(String, byte[]) calls cstmt.setBytes with null directly
        CallableQuery result = callableQuery.setBytes("data", (byte[]) null);
        assertSame(callableQuery, result);
        verify(callableStatement).setBytes("data", null);
    }

    // setAsciiStream(String, InputStream, long) - line 1025
    @Test
    public void testSetAsciiStream_ByName_WithLongLength() throws SQLException {
        InputStream is = new ByteArrayInputStream("data".getBytes());
        callableQuery.setAsciiStream("param", is, 4L);
        verify(callableStatement).setAsciiStream("param", is, 4L);
    }

    // Tests for setAsciiStream by name
    @Test
    public void testSetAsciiStream_ByName_NonNull() throws SQLException {
        InputStream is = new ByteArrayInputStream("hello".getBytes());
        CallableQuery result = callableQuery.setAsciiStream("stream", is);
        assertSame(callableQuery, result);
        verify(callableStatement).setAsciiStream("stream", is);
    }

    // setBinaryStream(String, InputStream, long) - line 1068
    @Test
    public void testSetBinaryStream_ByName_WithLongLength() throws SQLException {
        InputStream is = new ByteArrayInputStream(new byte[] { 1, 2 });
        callableQuery.setBinaryStream("param", is, 2L);
        verify(callableStatement).setBinaryStream("param", is, 2L);
    }

    // Tests for setBinaryStream by name
    @Test
    public void testSetBinaryStream_ByName_NonNull() throws SQLException {
        InputStream is = new ByteArrayInputStream(new byte[] { 1, 2 });
        CallableQuery result = callableQuery.setBinaryStream("stream", is);
        assertSame(callableQuery, result);
        verify(callableStatement).setBinaryStream("stream", is);
    }

    // setCharacterStream(String, Reader, long) - line 1110
    @Test
    public void testSetCharacterStream_ByName_WithLongLength() throws SQLException {
        Reader reader = new StringReader("data");
        callableQuery.setCharacterStream("param", reader, 4L);
        verify(callableStatement).setCharacterStream("param", reader, 4L);
    }

    // Tests for setCharacterStream by name
    @Test
    public void testSetCharacterStream_ByName_NonNull() throws SQLException {
        Reader reader = new StringReader("hello");
        CallableQuery result = callableQuery.setCharacterStream("stream", reader);
        assertSame(callableQuery, result);
        verify(callableStatement).setCharacterStream("stream", reader);
    }

    // setNCharacterStream(String, Reader)
    @Test
    public void testSetNCharacterStream_ByName() throws SQLException {
        Reader reader = new StringReader("nchar");
        callableQuery.setNCharacterStream("param", reader);
        verify(callableStatement).setNCharacterStream("param", reader);
    }

    // setNCharacterStream(String, Reader, long)
    @Test
    public void testSetNCharacterStream_ByName_WithLength() throws SQLException {
        Reader reader = new StringReader("nchar");
        callableQuery.setNCharacterStream("param", reader, 5L);
        verify(callableStatement).setNCharacterStream("param", reader, 5L);
    }

    // setBlob(String, Blob)
    @Test
    public void testSetBlob_ByName_Blob() throws SQLException {
        Blob blob = Mockito.mock(Blob.class);
        callableQuery.setBlob("param", blob);
        verify(callableStatement).setBlob("param", blob);
    }

    // setBlob(String, InputStream)
    @Test
    public void testSetBlob_ByName_InputStream() throws SQLException {
        InputStream is = new ByteArrayInputStream(new byte[] { 1 });
        callableQuery.setBlob("param", is);
        verify(callableStatement).setBlob("param", is);
    }

    // setBlob(String, InputStream, long)
    @Test
    public void testSetBlob_ByName_InputStreamWithLength() throws SQLException {
        InputStream is = new ByteArrayInputStream(new byte[] { 1 });
        callableQuery.setBlob("param", is, 1L);
        verify(callableStatement).setBlob("param", is, 1L);
    }

    // setClob(String, Clob)
    @Test
    public void testSetClob_ByName_Clob() throws SQLException {
        Clob clob = Mockito.mock(Clob.class);
        callableQuery.setClob("param", clob);
        verify(callableStatement).setClob("param", clob);
    }

    // setClob(String, Reader)
    @Test
    public void testSetClob_ByName_Reader() throws SQLException {
        Reader reader = new StringReader("clob");
        callableQuery.setClob("param", reader);
        verify(callableStatement).setClob("param", reader);
    }

    // setClob(String, Reader, long)
    @Test
    public void testSetClob_ByName_ReaderWithLength() throws SQLException {
        Reader reader = new StringReader("clob");
        callableQuery.setClob("param", reader, 4L);
        verify(callableStatement).setClob("param", reader, 4L);
    }

    // setNClob(String, NClob)
    @Test
    public void testSetNClob_ByName_NClob() throws SQLException {
        NClob nclob = Mockito.mock(NClob.class);
        callableQuery.setNClob("param", nclob);
        verify(callableStatement).setNClob("param", nclob);
    }

    // setNClob(String, Reader)
    @Test
    public void testSetNClob_ByName_Reader() throws SQLException {
        Reader reader = new StringReader("nclob");
        callableQuery.setNClob("param", reader);
        verify(callableStatement).setNClob("param", reader);
    }

    // setNClob(String, Reader, long)
    @Test
    public void testSetNClob_ByName_ReaderWithLength() throws SQLException {
        Reader reader = new StringReader("nclob");
        callableQuery.setNClob("param", reader, 5L);
        verify(callableStatement).setNClob("param", reader, 5L);
    }

    // setURL(String, URL)
    @Test
    public void testSetURL_ByName() throws Exception {
        URL url = new URL("https://example.com");
        callableQuery.setURL("param", url);
        verify(callableStatement).setURL("param", url);
    }

    // setSQLXML(String, SQLXML)
    @Test
    public void testSetSQLXML_ByName() throws SQLException {
        SQLXML sqlxml = Mockito.mock(SQLXML.class);
        callableQuery.setSQLXML("param", sqlxml);
        verify(callableStatement).setSQLXML("param", sqlxml);
    }

    // setRowId(String, RowId)
    @Test
    public void testSetRowId_ByName() throws SQLException {
        RowId rowId = Mockito.mock(RowId.class);
        callableQuery.setRowId("param", rowId);
        verify(callableStatement).setRowId("param", rowId);
    }

    @Test
    public void testSetObject_ByName_WithSqlType() throws SQLException {
        CallableQuery result = callableQuery.setObject("obj", "value", Types.VARCHAR);
        assertSame(callableQuery, result);
        verify(callableStatement).setObject("obj", "value", Types.VARCHAR);
    }

    // setObject(String, Object, int, int) - with scale
    @Test
    public void testSetObject_ByName_WithSqlTypeAndScale() throws SQLException {
        CallableQuery result = callableQuery.setObject("param", 123.456, Types.DECIMAL, 2);
        assertSame(callableQuery, result);
        verify(callableStatement).setObject("param", 123.456, Types.DECIMAL, 2);
    }

    // ============================================================================
    // setObject(String, Object, SQLType) — L1552, L1554
    // ============================================================================

    @Test
    public void testSetObject_ByName_SQLType() throws SQLException {
        CallableQuery result = callableQuery.setObject("amount", 123.45, JDBCType.DECIMAL);
        assertSame(callableQuery, result);
        verify(callableStatement).setObject("amount", 123.45, JDBCType.DECIMAL);
    }

    // setObject(String, Object, SQLType, int) — L1576, L1578
    @Test
    public void testSetObject_ByName_SQLTypeAndScale() throws SQLException {
        BigDecimal val = new BigDecimal("99.999");
        CallableQuery result = callableQuery.setObject("price", val, JDBCType.DECIMAL, 2);
        assertSame(callableQuery, result);
        verify(callableStatement).setObject("price", val, JDBCType.DECIMAL, 2);
    }

    // setObject(String, T, Type<T>) happy path — delegates to type.set(cstmt, name, value) — L1601, L1603, L1605
    @SuppressWarnings("unchecked")
    @Test
    public void testSetObject_ByName_WithType() throws SQLException {
        com.landawn.abacus.type.Type<String> type = Mockito.mock(com.landawn.abacus.type.Type.class);
        CallableQuery result = callableQuery.setObject("preferences", "json-value", type);
        assertSame(callableQuery, result);
        verify(type).set(callableStatement, "preferences", "json-value");
    }

    // Tests for setObject by name (null: goes through cstmt.setObject)
    @Test
    public void testSetObject_ByName_Null() throws SQLException {
        CallableQuery result = callableQuery.setObject("obj", (Object) null);
        assertSame(callableQuery, result);
        verify(callableStatement).setObject("obj", null);
    }

    @Test
    public void testSetObjectByNameNullSQLTypeRejectedBeforeDriverCall() throws SQLException {
        assertThrows(IllegalArgumentException.class, () -> callableQuery.setObject("amount", 123.45, (SQLType) null));

        verify(callableStatement, never()).setObject("amount", 123.45, (SQLType) null);
        verify(callableStatement).close();
    }

    @Test
    public void testSetObjectByNameNullSQLTypeAndScaleRejectedBeforeDriverCall() throws SQLException {
        assertThrows(IllegalArgumentException.class, () -> callableQuery.setObject("amount", BigDecimal.ONE, (SQLType) null, 2));

        verify(callableStatement, never()).setObject("amount", BigDecimal.ONE, (SQLType) null, 2);
        verify(callableStatement).close();
    }

    // setObject(String, T, Type<T>) null-type validation — checkArgNotNull(type, cs.type) — L1601
    @Test
    public void testSetObject_ByName_WithType_NullType() throws SQLException {
        // checkArgNotNull may close the query before throwing IAE, so use a fresh instance.
        CallableStatement cs = Mockito.mock(CallableStatement.class);
        Connection conn = Mockito.mock(Connection.class);
        when(cs.getConnection()).thenReturn(conn);
        CallableQuery cq = new CallableQuery(cs);
        assertThrows(IllegalArgumentException.class, () -> cq.setObject("p", "v", (com.landawn.abacus.type.Type<String>) null));
    }

    // setParameters(Map) - sets multiple params from a map
    @Test
    public void testSetParameters_WithMap() throws SQLException {
        Map<String, Object> params = new HashMap<>();
        params.put("name", "Alice");
        CallableQuery result = callableQuery.setParameters(params);
        assertSame(callableQuery, result);
        verify(callableStatement).setString("name", "Alice");
    }

    // setParameters(Object entity, List<String> parameterNames) — not previously tested
    @Test
    public void testSetParameters_EntityValid() throws SQLException {
        SimpleTestBean bean = new SimpleTestBean();
        bean.setName("Alice");
        callableQuery.setParameters(bean, java.util.List.of("name"));
        verify(callableStatement).setString("name", "Alice");
    }

    // ============================================================================
    // setParameters(Object) name-based binding dispatch — L1736-1747
    // ============================================================================

    // bean → setParameters(parameters, Beans.getPropNameList(cls)) — L1740, L1741
    @Test
    public void testSetParameters_ByObject_Bean() throws SQLException {
        SimpleTestBean bean = new SimpleTestBean();
        bean.setName("Alice");
        CallableQuery result = callableQuery.setParameters(bean);
        assertSame(callableQuery, result);
        verify(callableStatement).setString("name", "Alice");
    }

    // Map → setParameters((Map) parameters) — L1742, L1743
    @Test
    public void testSetParameters_ByObject_Map() throws SQLException {
        Map<String, Object> params = new HashMap<>();
        params.put("dept", "Sales");
        CallableQuery result = callableQuery.setParameters((Object) params);
        assertSame(callableQuery, result);
        verify(callableStatement).setString("dept", "Sales");
    }

    // neither bean nor Map → close() + IllegalArgumentException with message substring — L1744-1747
    @Test
    public void testSetParameters_ByObject_UnsupportedType() throws SQLException {
        CallableStatement cs = Mockito.mock(CallableStatement.class);
        Connection conn = Mockito.mock(Connection.class);
        when(cs.getConnection()).thenReturn(conn);
        CallableQuery cq = new CallableQuery(cs);
        // Integer is neither a bean (Number subclass) nor a Map → unsupported path.
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class, () -> cq.setParameters(Integer.valueOf(42)));
        assertTrue(ex.getMessage().contains("Unsupported parameter type for name-based binding"));
        verify(cs).close();
    }

    @Test
    public void testSetParameters_EntityInvalidParam_Throws() throws SQLException {
        SimpleTestBean bean = new SimpleTestBean();
        assertThrows(IllegalArgumentException.class, () -> callableQuery.setParameters(bean, java.util.List.of("nonExistent")));
    }

    // null parameters → IllegalArgumentException — L1736 (fresh instance; checkArgNotNull may close)
    @Test
    public void testSetParameters_ByObject_NullParameters() throws SQLException {
        CallableStatement cs = Mockito.mock(CallableStatement.class);
        Connection conn = Mockito.mock(Connection.class);
        when(cs.getConnection()).thenReturn(conn);
        CallableQuery cq = new CallableQuery(cs);
        assertThrows(IllegalArgumentException.class, () -> cq.setParameters((Object) null));
    }

    // A non-bean entity must close the query before the IAE, like every other failure of this method
    // (and like NamedQuery.setParameters(Object, Collection)); it previously leaked the open statement.
    @Test
    public void testSetParameters_EntityNotBean_ClosesQuery() throws SQLException {
        final IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> callableQuery.setParameters(Integer.valueOf(42), java.util.List.of("value")));

        verify(callableStatement).close();
        assertThrows(IllegalStateException.class, () -> callableQuery.executeAndGetOutParameters());
        assertTrue(ex.getMessage().contains("Unsupported parameter type"));
    }

    // --- setParameters(Map) throws → close() called (L1522-1524) ---

    @Test
    public void testSetParameters_ClosesOnException() throws SQLException {
        Map<String, Object> params = new HashMap<>();
        params.put("key", "value");
        doThrow(new SQLException("boom")).when(callableStatement).setString("key", "value");
        assertThrows(SQLException.class, () -> callableQuery.setParameters(params));
        verify(callableStatement).close();
    }

    @Test
    public void testSetParameters_ClosesOnError() throws SQLException {
        final Map<String, Object> params = new HashMap<>();
        params.put("key", "value");
        final AssertionError bindingError = new AssertionError("boom");
        doThrow(bindingError).when(callableStatement).setString("key", "value");

        assertSame(bindingError, assertThrows(AssertionError.class, () -> callableQuery.setParameters(params)));
        verify(callableStatement).close();
    }

    @Test
    public void testSetParametersPreservesBindingFailureWhenCloseHandlerThrows() throws SQLException {
        final Map<String, Object> params = Map.of("key", "value");
        final SQLException bindingFailure = new SQLException("binding failed");
        final RuntimeException closeFailure = new RuntimeException("close handler failed");
        doThrow(bindingFailure).when(callableStatement).setString("key", "value");
        callableQuery.onClose(() -> {
            throw closeFailure;
        });

        final SQLException thrown = assertThrows(SQLException.class, () -> callableQuery.setParameters(params));

        assertSame(bindingFailure, thrown);
        assertEquals(1, thrown.getSuppressed().length);
        assertSame(closeFailure, thrown.getSuppressed()[0]);
    }

    @Test
    public void testRegisterOutParameter_ReplacesExistingNamedParameter() throws SQLException {
        when(callableStatement.execute()).thenReturn(true);

        callableQuery.registerOutParameter("total", Types.INTEGER);
        callableQuery.registerOutParameter("total", Types.DECIMAL, 2);

        Boolean isResultSet = callableQuery.executeThenApply((stmt, outParams, firstResultSet) -> {
            assertSame(callableStatement, stmt);
            assertEquals(1, outParams.size());
            assertEquals("total", outParams.get(0).parameterName());
            assertEquals(Types.DECIMAL, outParams.get(0).sqlType());
            assertEquals(2, outParams.get(0).scale());
            return firstResultSet;
        });

        assertTrue(isResultSet);
        verify(callableStatement).registerOutParameter("total", Types.INTEGER);
        verify(callableStatement).registerOutParameter("total", Types.DECIMAL, 2);
        verify(callableStatement).execute();
    }

    // --- registerOutParameter(int, int) – by index with int sqlType (L1602) ---

    @Test
    public void testRegisterOutParameter_ByIndex_SqlType() throws SQLException {
        CallableQuery result = callableQuery.registerOutParameter(1, Types.INTEGER);
        assertSame(callableQuery, result);
        verify(callableStatement).registerOutParameter(1, Types.INTEGER);
    }

    // --- registerOutParameter(int, int, int) – by index with scale (L1637) ---

    @Test
    public void testRegisterOutParameter_ByIndex_SqlTypeAndScale() throws SQLException {
        CallableQuery result = callableQuery.registerOutParameter(2, Types.DECIMAL, 4);
        assertSame(callableQuery, result);
        verify(callableStatement).registerOutParameter(2, Types.DECIMAL, 4);
    }

    // --- registerOutParameter(int, int, String) – by index with typeName (L1675) ---

    @Test
    public void testRegisterOutParameter_ByIndex_SqlTypeAndTypeName() throws SQLException {
        CallableQuery result = callableQuery.registerOutParameter(3, Types.STRUCT, "MY_STRUCT");
        assertSame(callableQuery, result);
        verify(callableStatement).registerOutParameter(3, Types.STRUCT, "MY_STRUCT");
    }

    // --- registerOutParameter(String, int, String) – by name with typeName (L1772) ---

    @Test
    public void testRegisterOutParameter_ByName_SqlTypeAndTypeName() throws SQLException {
        CallableQuery result = callableQuery.registerOutParameter("result", Types.STRUCT, "SCHEMA.MY_TYPE");
        assertSame(callableQuery, result);
        verify(callableStatement).registerOutParameter("result", Types.STRUCT, "SCHEMA.MY_TYPE");
    }

    // --- registerOutParameter(int, SQLType) – by index with SQLType (L1800) ---

    @Test
    public void testRegisterOutParameter_ByIndex_SQLType() throws SQLException {
        CallableQuery result = callableQuery.registerOutParameter(1, JDBCType.INTEGER);
        assertSame(callableQuery, result);
        verify(callableStatement).registerOutParameter(1, JDBCType.INTEGER);
    }

    // --- registerOutParameter(int, SQLType, int) – by index with SQLType and scale (L1829) ---

    @Test
    public void testRegisterOutParameter_ByIndex_SQLTypeAndScale() throws SQLException {
        CallableQuery result = callableQuery.registerOutParameter(2, JDBCType.DECIMAL, 2);
        assertSame(callableQuery, result);
        verify(callableStatement).registerOutParameter(2, JDBCType.DECIMAL, 2);
    }

    // --- registerOutParameter(int, SQLType, String) – by index with SQLType and typeName (L1858) ---

    @Test
    public void testRegisterOutParameter_ByIndex_SQLTypeAndTypeName() throws SQLException {
        CallableQuery result = callableQuery.registerOutParameter(3, JDBCType.STRUCT, "MY_STRUCT");
        assertSame(callableQuery, result);
        verify(callableStatement).registerOutParameter(3, JDBCType.STRUCT, "MY_STRUCT");
    }

    // --- registerOutParameter(String, SQLType) – by name with SQLType (L1886) ---

    @Test
    public void testRegisterOutParameter_ByName_SQLType() throws SQLException {
        CallableQuery result = callableQuery.registerOutParameter("total", JDBCType.INTEGER);
        assertSame(callableQuery, result);
        verify(callableStatement).registerOutParameter("total", JDBCType.INTEGER);
    }

    // --- registerOutParameter(String, SQLType, int) – by name with SQLType and scale (L1915) ---

    @Test
    public void testRegisterOutParameter_ByName_SQLTypeAndScale() throws SQLException {
        CallableQuery result = callableQuery.registerOutParameter("price", JDBCType.DECIMAL, 2);
        assertSame(callableQuery, result);
        verify(callableStatement).registerOutParameter("price", JDBCType.DECIMAL, 2);
    }

    // --- registerOutParameter(String, SQLType, String) – by name with SQLType and typeName (L1945) ---

    @Test
    public void testRegisterOutParameter_ByName_SQLTypeAndTypeName() throws SQLException {
        CallableQuery result = callableQuery.registerOutParameter("obj", JDBCType.STRUCT, "MY_TYPE");
        assertSame(callableQuery, result);
        verify(callableStatement).registerOutParameter("obj", JDBCType.STRUCT, "MY_TYPE");
    }

    // --- registerOutParameter by index replacement (L2078 PC) ---

    @Test
    public void testRegisterOutParameter_ReplacesExistingIndexedParameter() throws SQLException {
        when(callableStatement.execute()).thenReturn(true);

        callableQuery.registerOutParameter(1, Types.INTEGER);
        callableQuery.registerOutParameter(1, Types.DECIMAL, 2);

        Boolean isResultSet = callableQuery.executeThenApply((stmt, outParams, firstResultSet) -> {
            assertSame(callableStatement, stmt);
            assertEquals(1, outParams.size());
            assertEquals(1, outParams.get(0).parameterIndex());
            assertEquals(Types.DECIMAL, outParams.get(0).sqlType());
            assertEquals(2, outParams.get(0).scale());
            return firstResultSet;
        });

        assertTrue(isResultSet);
        verify(callableStatement).registerOutParameter(1, Types.INTEGER);
        verify(callableStatement).registerOutParameter(1, Types.DECIMAL, 2);
        verify(callableStatement).execute();
    }

    @Test
    public void testRegisterOutParameter_SQLTypeRejectsNullVendorTypeNumber() {
        SQLType sqlType = new SQLType() {
            @Override
            public String getName() {
                return "NULL_VENDOR";
            }

            @Override
            public String getVendor() {
                return "test";
            }

            @Override
            public Integer getVendorTypeNumber() {
                return null;
            }
        };

        assertThrows(IllegalArgumentException.class, () -> callableQuery.registerOutParameter(1, sqlType));
    }

    @Test
    public void testRegisterOutParameters_WithContextObject() throws SQLException {
        List<String> names = List.of("first", "second");

        CallableQuery result = callableQuery.registerOutParameters(names, (query, value) -> {
            query.registerOutParameter(value.get(0), Types.INTEGER);
            query.registerOutParameter(value.get(1), Types.VARCHAR);
        });

        assertNotNull(result);
        assertSame(callableQuery, result);
        verify(callableStatement).registerOutParameter("first", Types.INTEGER);
        verify(callableStatement).registerOutParameter("second", Types.VARCHAR);
    }

    // --- registerOutParameters(ParametersSetter) happy path (L1984, L1986, L1987, L1993) ---

    @Test
    public void testRegisterOutParameters_Consumer_HappyPath() throws SQLException {
        CallableQuery result = callableQuery.registerOutParameters(q -> q.registerOutParameter(1, Types.INTEGER));
        assertSame(callableQuery, result);
        verify(callableStatement).registerOutParameter(1, Types.INTEGER);
    }

    @Test
    public void testRegisterOutParameters_NullSetter() {
        assertThrows(IllegalArgumentException.class, () -> callableQuery.registerOutParameters((Jdbc.ParametersSetter<? super CallableQuery>) null));
    }

    @Test
    public void testRegisterOutParameters_ClosesStatementOnFailure() throws SQLException {
        assertThrows(SQLException.class, () -> callableQuery.registerOutParameters(query -> {
            throw new SQLException("boom");
        }));

        verify(callableStatement).close();
    }

    // --- registerOutParameters(T, BiParametersSetter) exception closes query (L2047) ---

    @Test
    public void testRegisterOutParameters_BiSetter_ClosesOnException() throws SQLException {
        assertThrows(RuntimeException.class, () -> callableQuery.registerOutParameters("ctx", (q, ctx) -> {
            throw new RuntimeException("error");
        }));
        verify(callableStatement).close();
    }

    private static class SimpleTestBean {
        private String name;

        public String getName() {
            return name;
        }

        public void setName(final String name) {
            this.name = name;
        }
    }
}
