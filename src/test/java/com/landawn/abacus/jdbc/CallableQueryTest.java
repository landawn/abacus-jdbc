package com.landawn.abacus.jdbc;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.io.Reader;
import java.io.StringReader;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.net.MalformedURLException;
import java.net.URL;
import java.sql.CallableStatement;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
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
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import com.landawn.abacus.TestBase;

public class CallableQueryTest extends TestBase {

    @Mock
    private CallableStatement mockCallableStatement;

    @Mock
    private Connection mockConnection;

    @Mock
    private ResultSet mockResultSet;

    @Mock
    private ResultSetMetaData mockResultSetMetaData;

    private CallableQuery callableQuery;

    @BeforeEach
    public void setUp() throws Exception {
        MockitoAnnotations.openMocks(this);
        when(mockCallableStatement.getConnection()).thenReturn(mockConnection);
        callableQuery = new CallableQuery(mockCallableStatement);
    }

    @Test
    public void testSetNullWithParameterName() throws SQLException {
        String paramName = "testParam";
        int sqlType = Types.INTEGER;

        CallableQuery result = callableQuery.setNull(paramName, sqlType);

        verify(mockCallableStatement).setNull(paramName, sqlType);
        assertSame(callableQuery, result);
    }

    @Test
    public void testSetNullWithParameterNameAndTypeName() throws SQLException {
        String paramName = "testParam";
        int sqlType = Types.STRUCT;
        String typeName = "MY_TYPE";

        CallableQuery result = callableQuery.setNull(paramName, sqlType, typeName);

        verify(mockCallableStatement).setNull(paramName, sqlType, typeName);
        assertSame(callableQuery, result);
    }

    @Test
    public void testSetBooleanPrimitive() throws SQLException {
        String paramName = "isActive";
        boolean value = true;

        CallableQuery result = callableQuery.setBoolean(paramName, value);

        verify(mockCallableStatement).setBoolean(paramName, value);
        assertSame(callableQuery, result);
    }

    @Test
    public void testSetBooleanObject() throws SQLException {
        String paramName = "isActive";
        Boolean value = Boolean.TRUE;

        CallableQuery result = callableQuery.setBoolean(paramName, value);

        verify(mockCallableStatement).setBoolean(paramName, true);
        assertSame(callableQuery, result);
    }

    @Test
    public void testSetBooleanObjectNull() throws SQLException {
        String paramName = "isActive";
        Boolean value = null;

        CallableQuery result = callableQuery.setBoolean(paramName, value);

        verify(mockCallableStatement).setNull(paramName, Types.BOOLEAN);
        assertSame(callableQuery, result);
    }

    @Test
    public void testSetBytePrimitive() throws SQLException {
        String paramName = "byteParam";
        byte value = 127;

        CallableQuery result = callableQuery.setByte(paramName, value);

        verify(mockCallableStatement).setByte(paramName, value);
        assertSame(callableQuery, result);
    }

    @Test
    public void testSetByteObject() throws SQLException {
        String paramName = "byteParam";
        Byte value = Byte.valueOf((byte) 100);

        CallableQuery result = callableQuery.setByte(paramName, value);

        verify(mockCallableStatement).setByte(paramName, (byte) 100);
        assertSame(callableQuery, result);
    }

    @Test
    public void testSetByteObjectNull() throws SQLException {
        String paramName = "byteParam";
        Byte value = null;

        CallableQuery result = callableQuery.setByte(paramName, value);

        verify(mockCallableStatement).setNull(paramName, Types.TINYINT);
        assertSame(callableQuery, result);
    }

    @Test
    public void testSetShortPrimitive() throws SQLException {
        String paramName = "shortParam";
        short value = 1000;

        CallableQuery result = callableQuery.setShort(paramName, value);

        verify(mockCallableStatement).setShort(paramName, value);
        assertSame(callableQuery, result);
    }

    @Test
    public void testSetShortObject() throws SQLException {
        String paramName = "shortParam";
        Short value = Short.valueOf((short) 2000);

        CallableQuery result = callableQuery.setShort(paramName, value);

        verify(mockCallableStatement).setShort(paramName, (short) 2000);
        assertSame(callableQuery, result);
    }

    @Test
    public void testSetShortObjectNull() throws SQLException {
        String paramName = "shortParam";
        Short value = null;

        CallableQuery result = callableQuery.setShort(paramName, value);

        verify(mockCallableStatement).setNull(paramName, Types.SMALLINT);
        assertSame(callableQuery, result);
    }

    @Test
    public void testSetIntPrimitive() throws SQLException {
        String paramName = "intParam";
        int value = 12345;

        CallableQuery result = callableQuery.setInt(paramName, value);

        verify(mockCallableStatement).setInt(paramName, value);
        assertSame(callableQuery, result);
    }

    @Test
    public void testSetIntObject() throws SQLException {
        String paramName = "intParam";
        Integer value = Integer.valueOf(54321);

        CallableQuery result = callableQuery.setInt(paramName, value);

        verify(mockCallableStatement).setInt(paramName, 54321);
        assertSame(callableQuery, result);
    }

    @Test
    public void testSetIntObjectNull() throws SQLException {
        String paramName = "intParam";
        Integer value = null;

        CallableQuery result = callableQuery.setInt(paramName, value);

        verify(mockCallableStatement).setNull(paramName, Types.INTEGER);
        assertSame(callableQuery, result);
    }

    @Test
    public void testSetLongPrimitive() throws SQLException {
        String paramName = "longParam";
        long value = 1234567890L;

        CallableQuery result = callableQuery.setLong(paramName, value);

        verify(mockCallableStatement).setLong(paramName, value);
        assertSame(callableQuery, result);
    }

    @Test
    public void testSetLongObject() throws SQLException {
        String paramName = "longParam";
        Long value = Long.valueOf(9876543210L);

        CallableQuery result = callableQuery.setLong(paramName, value);

        verify(mockCallableStatement).setLong(paramName, 9876543210L);
        assertSame(callableQuery, result);
    }

    @Test
    public void testSetLongObjectNull() throws SQLException {
        String paramName = "longParam";
        Long value = null;

        CallableQuery result = callableQuery.setLong(paramName, value);

        verify(mockCallableStatement).setNull(paramName, Types.BIGINT);
        assertSame(callableQuery, result);
    }

    @Test
    public void testSetLongBigInteger() throws SQLException {
        String paramName = "bigIntParam";
        BigInteger value = new BigInteger("123456789012345690");

        CallableQuery result = callableQuery.setLong(paramName, value);

        verify(mockCallableStatement).setLong(paramName, value.longValueExact());
        assertSame(callableQuery, result);
    }

    @Test
    public void testSetLongBigIntegerNull() throws SQLException {
        String paramName = "bigIntParam";
        BigInteger value = null;

        CallableQuery result = callableQuery.setLong(paramName, value);

        verify(mockCallableStatement).setNull(paramName, Types.BIGINT);
        assertSame(callableQuery, result);
    }

    @Test
    public void testSetFloatPrimitive() throws SQLException {
        String paramName = "floatParam";
        float value = 123.45f;

        CallableQuery result = callableQuery.setFloat(paramName, value);

        verify(mockCallableStatement).setFloat(paramName, value);
        assertSame(callableQuery, result);
    }

    @Test
    public void testSetFloatObject() throws SQLException {
        String paramName = "floatParam";
        Float value = Float.valueOf(543.21f);

        CallableQuery result = callableQuery.setFloat(paramName, value);

        verify(mockCallableStatement).setFloat(paramName, 543.21f);
        assertSame(callableQuery, result);
    }

    @Test
    public void testSetFloatObjectNull() throws SQLException {
        String paramName = "floatParam";
        Float value = null;

        CallableQuery result = callableQuery.setFloat(paramName, value);

        verify(mockCallableStatement).setNull(paramName, Types.FLOAT);
        assertSame(callableQuery, result);
    }

    @Test
    public void testSetDoublePrimitive() throws SQLException {
        String paramName = "doubleParam";
        double value = 123.456789;

        CallableQuery result = callableQuery.setDouble(paramName, value);

        verify(mockCallableStatement).setDouble(paramName, value);
        assertSame(callableQuery, result);
    }

    @Test
    public void testSetDoubleObject() throws SQLException {
        String paramName = "doubleParam";
        Double value = Double.valueOf(987.654321);

        CallableQuery result = callableQuery.setDouble(paramName, value);

        verify(mockCallableStatement).setDouble(paramName, 987.654321);
        assertSame(callableQuery, result);
    }

    @Test
    public void testSetDoubleObjectNull() throws SQLException {
        String paramName = "doubleParam";
        Double value = null;

        CallableQuery result = callableQuery.setDouble(paramName, value);

        verify(mockCallableStatement).setNull(paramName, Types.DOUBLE);
        assertSame(callableQuery, result);
    }

    @Test
    public void testSetBigDecimal() throws SQLException {
        String paramName = "decimalParam";
        BigDecimal value = new BigDecimal("123.45");

        CallableQuery result = callableQuery.setBigDecimal(paramName, value);

        verify(mockCallableStatement).setBigDecimal(paramName, value);
        assertSame(callableQuery, result);
    }

    @Test
    public void testSetBigDecimalBigInteger() throws SQLException {
        String paramName = "decimalParam";
        BigInteger value = new BigInteger("12345678901234567890");

        CallableQuery result = callableQuery.setBigDecimal(paramName, value);

        verify(mockCallableStatement).setBigDecimal(paramName, new BigDecimal(value));
        assertSame(callableQuery, result);
    }

    @Test
    public void testSetBigDecimalBigIntegerNull() throws SQLException {
        String paramName = "decimalParam";
        BigInteger value = null;

        CallableQuery result = callableQuery.setBigDecimal(paramName, value);

        verify(mockCallableStatement).setNull(paramName, Types.DECIMAL);
        assertSame(callableQuery, result);
    }

    @Test
    public void testSetBigIntegerAsString() throws SQLException {
        String paramName = "bigIntParam";
        BigInteger value = new BigInteger("99999999999999999999");

        CallableQuery result = callableQuery.setBigIntegerAsString(paramName, value);

        verify(mockCallableStatement).setString(paramName, value.toString(10));
        assertSame(callableQuery, result);
    }

    @Test
    public void testSetString() throws SQLException {
        String paramName = "stringParam";
        String value = "test string";

        CallableQuery result = callableQuery.setString(paramName, value);

        verify(mockCallableStatement).setString(paramName, value);
        assertSame(callableQuery, result);
    }

    @Test
    public void testSetStringCharSequence() throws SQLException {
        String paramName = "stringParam";
        CharSequence value = new StringBuilder("test sequence");

        CallableQuery result = callableQuery.setString(paramName, value);

        verify(mockCallableStatement).setString(paramName, "test sequence");
        assertSame(callableQuery, result);
    }

    @Test
    public void testSetStringCharSequenceNull() throws SQLException {
        String paramName = "stringParam";
        CharSequence value = null;

        CallableQuery result = callableQuery.setString(paramName, value);

        verify(mockCallableStatement).setString(paramName, null);
        assertSame(callableQuery, result);
    }

    @Test
    public void testSetStringChar() throws SQLException {
        String paramName = "charParam";
        char value = 'A';

        CallableQuery result = callableQuery.setString(paramName, value);

        verify(mockCallableStatement).setString(paramName, "A");
        assertSame(callableQuery, result);
    }

    @Test
    public void testSetStringCharacter() throws SQLException {
        String paramName = "charParam";
        Character value = Character.valueOf('B');

        CallableQuery result = callableQuery.setString(paramName, value);

        verify(mockCallableStatement).setString(paramName, "B");
        assertSame(callableQuery, result);
    }

    @Test
    public void testSetStringCharacterNull() throws SQLException {
        String paramName = "charParam";
        Character value = null;

        CallableQuery result = callableQuery.setString(paramName, value);

        verify(mockCallableStatement).setString(paramName, null);
        assertSame(callableQuery, result);
    }

    @Test
    public void testSetStringBigInteger() throws SQLException {
        String paramName = "bigIntParam";
        BigInteger value = new BigInteger("123456789012345");

        CallableQuery result = callableQuery.setString(paramName, value);

        verify(mockCallableStatement).setString(paramName, value.toString(10));
        assertSame(callableQuery, result);
    }

    @Test
    public void testSetStringBigIntegerNull() throws SQLException {
        String paramName = "bigIntParam";
        BigInteger value = null;

        CallableQuery result = callableQuery.setString(paramName, value);

        verify(mockCallableStatement).setNull(paramName, Types.VARCHAR);
        assertSame(callableQuery, result);
    }

    @Test
    public void testSetNString() throws SQLException {
        String paramName = "nstringParam";
        String value = "unicode string";

        CallableQuery result = callableQuery.setNString(paramName, value);

        verify(mockCallableStatement).setNString(paramName, value);
        assertSame(callableQuery, result);
    }

    @Test
    public void testSetDateSqlDate() throws SQLException {
        String paramName = "dateParam";
        java.sql.Date value = java.sql.Date.valueOf("2023-01-15");

        CallableQuery result = callableQuery.setDate(paramName, value);

        verify(mockCallableStatement).setDate(paramName, value);
        assertSame(callableQuery, result);
    }

    @Test
    public void testSetDateUtilDate() throws SQLException {
        String paramName = "dateParam";
        java.util.Date value = new java.util.Date();
        java.sql.Date sqlDate = new java.sql.Date(value.getTime());

        CallableQuery result = callableQuery.setDate(paramName, value);

        verify(mockCallableStatement).setDate(eq(paramName), any(java.sql.Date.class));
        assertSame(callableQuery, result);
    }

    @Test
    public void testSetDateUtilDateNull() throws SQLException {
        String paramName = "dateParam";
        java.util.Date value = null;

        CallableQuery result = callableQuery.setDate(paramName, value);

        verify(mockCallableStatement).setDate(paramName, null);
        assertSame(callableQuery, result);
    }

    @Test
    public void testSetDateLocalDate() throws SQLException {
        String paramName = "dateParam";
        LocalDate value = LocalDate.of(2023, 1, 15);

        CallableQuery result = callableQuery.setDate(paramName, value);

        verify(mockCallableStatement).setDate(paramName, java.sql.Date.valueOf(value));
        assertSame(callableQuery, result);
    }

    @Test
    public void testSetDateLocalDateNull() throws SQLException {
        String paramName = "dateParam";
        LocalDate value = null;

        CallableQuery result = callableQuery.setDate(paramName, value);

        verify(mockCallableStatement).setDate(paramName, null);
        assertSame(callableQuery, result);
    }

    @Test
    public void testSetTimeSqlTime() throws SQLException {
        String paramName = "timeParam";
        java.sql.Time value = java.sql.Time.valueOf("10:30:00");

        CallableQuery result = callableQuery.setTime(paramName, value);

        verify(mockCallableStatement).setTime(paramName, value);
        assertSame(callableQuery, result);
    }

    @Test
    public void testSetTimeUtilDate() throws SQLException {
        String paramName = "timeParam";
        java.util.Date value = new java.util.Date();

        CallableQuery result = callableQuery.setTime(paramName, value);

        verify(mockCallableStatement).setTime(eq(paramName), any(java.sql.Time.class));
        assertSame(callableQuery, result);
    }

    @Test
    public void testSetTimeLocalTime() throws SQLException {
        String paramName = "timeParam";
        LocalTime value = LocalTime.of(14, 30, 0);

        CallableQuery result = callableQuery.setTime(paramName, value);

        verify(mockCallableStatement).setTime(paramName, java.sql.Time.valueOf(value));
        assertSame(callableQuery, result);
    }

    @Test
    public void testSetTimestamp() throws SQLException {
        String paramName = "timestampParam";
        Timestamp value = new Timestamp(System.currentTimeMillis());

        CallableQuery result = callableQuery.setTimestamp(paramName, value);

        verify(mockCallableStatement).setTimestamp(paramName, value);
        assertSame(callableQuery, result);
    }

    @Test
    public void testSetTimestampUtilDate() throws SQLException {
        String paramName = "timestampParam";
        java.util.Date value = new java.util.Date();

        CallableQuery result = callableQuery.setTimestamp(paramName, value);

        verify(mockCallableStatement).setTimestamp(eq(paramName), any(Timestamp.class));
        assertSame(callableQuery, result);
    }

    @Test
    public void testSetTimestampLocalDateTime() throws SQLException {
        String paramName = "timestampParam";
        LocalDateTime value = LocalDateTime.now();

        CallableQuery result = callableQuery.setTimestamp(paramName, value);

        verify(mockCallableStatement).setTimestamp(paramName, Timestamp.valueOf(value));
        assertSame(callableQuery, result);
    }

    @Test
    public void testSetTimestampZonedDateTime() throws SQLException {
        String paramName = "timestampParam";
        ZonedDateTime value = ZonedDateTime.now();

        CallableQuery result = callableQuery.setTimestamp(paramName, value);

        verify(mockCallableStatement).setTimestamp(paramName, Timestamp.from(value.toInstant()));
        assertSame(callableQuery, result);
    }

    @Test
    public void testSetTimestampOffsetDateTime() throws SQLException {
        String paramName = "timestampParam";
        OffsetDateTime value = OffsetDateTime.now();

        CallableQuery result = callableQuery.setTimestamp(paramName, value);

        verify(mockCallableStatement).setTimestamp(paramName, Timestamp.from(value.toInstant()));
        assertSame(callableQuery, result);
    }

    @Test
    public void testSetTimestampInstant() throws SQLException {
        String paramName = "timestampParam";
        Instant value = Instant.now();

        CallableQuery result = callableQuery.setTimestamp(paramName, value);

        verify(mockCallableStatement).setTimestamp(paramName, Timestamp.from(value));
        assertSame(callableQuery, result);
    }

    @Test
    public void testSetBytes() throws SQLException {
        String paramName = "bytesParam";
        byte[] value = new byte[] { 1, 2, 3, 4, 5 };

        CallableQuery result = callableQuery.setBytes(paramName, value);

        verify(mockCallableStatement).setBytes(paramName, value);
        assertSame(callableQuery, result);
    }

    @Test
    public void testSetAsciiStream() throws SQLException {
        String paramName = "asciiParam";
        InputStream value = new ByteArrayInputStream("test".getBytes());

        CallableQuery result = callableQuery.setAsciiStream(paramName, value);

        verify(mockCallableStatement).setAsciiStream(paramName, value);
        assertSame(callableQuery, result);
    }

    @Test
    public void testSetAsciiStreamWithLength() throws SQLException {
        String paramName = "asciiParam";
        InputStream value = new ByteArrayInputStream("test".getBytes());
        long length = 4L;

        CallableQuery result = callableQuery.setAsciiStream(paramName, value, length);

        verify(mockCallableStatement).setAsciiStream(paramName, value, length);
        assertSame(callableQuery, result);
    }

    @Test
    public void testSetBinaryStream() throws SQLException {
        String paramName = "binaryParam";
        InputStream value = new ByteArrayInputStream(new byte[] { 1, 2, 3 });

        CallableQuery result = callableQuery.setBinaryStream(paramName, value);

        verify(mockCallableStatement).setBinaryStream(paramName, value);
        assertSame(callableQuery, result);
    }

    @Test
    public void testSetBinaryStreamWithLength() throws SQLException {
        String paramName = "binaryParam";
        InputStream value = new ByteArrayInputStream(new byte[] { 1, 2, 3 });
        long length = 3L;

        CallableQuery result = callableQuery.setBinaryStream(paramName, value, length);

        verify(mockCallableStatement).setBinaryStream(paramName, value, length);
        assertSame(callableQuery, result);
    }

    @Test
    public void testSetCharacterStream() throws SQLException {
        String paramName = "charParam";
        Reader value = new StringReader("test");

        CallableQuery result = callableQuery.setCharacterStream(paramName, value);

        verify(mockCallableStatement).setCharacterStream(paramName, value);
        assertSame(callableQuery, result);
    }

    @Test
    public void testSetCharacterStreamWithLength() throws SQLException {
        String paramName = "charParam";
        Reader value = new StringReader("test");
        long length = 4L;

        CallableQuery result = callableQuery.setCharacterStream(paramName, value, length);

        verify(mockCallableStatement).setCharacterStream(paramName, value, length);
        assertSame(callableQuery, result);
    }

    @Test
    public void testSetNCharacterStream() throws SQLException {
        String paramName = "ncharParam";
        Reader value = new StringReader("unicode test");

        CallableQuery result = callableQuery.setNCharacterStream(paramName, value);

        verify(mockCallableStatement).setNCharacterStream(paramName, value);
        assertSame(callableQuery, result);
    }

    @Test
    public void testSetNCharacterStreamWithLength() throws SQLException {
        String paramName = "ncharParam";
        Reader value = new StringReader("unicode test");
        long length = 12L;

        CallableQuery result = callableQuery.setNCharacterStream(paramName, value, length);

        verify(mockCallableStatement).setNCharacterStream(paramName, value, length);
        assertSame(callableQuery, result);
    }

    @Test
    public void testSetBlob() throws SQLException {
        String paramName = "blobParam";
        java.sql.Blob value = mock(java.sql.Blob.class);

        CallableQuery result = callableQuery.setBlob(paramName, value);

        verify(mockCallableStatement).setBlob(paramName, value);
        assertSame(callableQuery, result);
    }

    @Test
    public void testSetBlobInputStream() throws SQLException {
        String paramName = "blobParam";
        InputStream value = new ByteArrayInputStream(new byte[] { 1, 2, 3 });

        CallableQuery result = callableQuery.setBlob(paramName, value);

        verify(mockCallableStatement).setBlob(paramName, value);
        assertSame(callableQuery, result);
    }

    @Test
    public void testSetBlobInputStreamWithLength() throws SQLException {
        String paramName = "blobParam";
        InputStream value = new ByteArrayInputStream(new byte[] { 1, 2, 3 });
        long length = 3L;

        CallableQuery result = callableQuery.setBlob(paramName, value, length);

        verify(mockCallableStatement).setBlob(paramName, value, length);
        assertSame(callableQuery, result);
    }

    @Test
    public void testSetClob() throws SQLException {
        String paramName = "clobParam";
        java.sql.Clob value = mock(java.sql.Clob.class);

        CallableQuery result = callableQuery.setClob(paramName, value);

        verify(mockCallableStatement).setClob(paramName, value);
        assertSame(callableQuery, result);
    }

    @Test
    public void testSetClobReader() throws SQLException {
        String paramName = "clobParam";
        Reader value = new StringReader("test");

        CallableQuery result = callableQuery.setClob(paramName, value);

        verify(mockCallableStatement).setClob(paramName, value);
        assertSame(callableQuery, result);
    }

    @Test
    public void testSetClobReaderWithLength() throws SQLException {
        String paramName = "clobParam";
        Reader value = new StringReader("test");
        long length = 4L;

        CallableQuery result = callableQuery.setClob(paramName, value, length);

        verify(mockCallableStatement).setClob(paramName, value, length);
        assertSame(callableQuery, result);
    }

    @Test
    public void testSetNClob() throws SQLException {
        String paramName = "nclobParam";
        java.sql.NClob value = mock(java.sql.NClob.class);

        CallableQuery result = callableQuery.setNClob(paramName, value);

        verify(mockCallableStatement).setNClob(paramName, value);
        assertSame(callableQuery, result);
    }

    @Test
    public void testSetNClobReader() throws SQLException {
        String paramName = "nclobParam";
        Reader value = new StringReader("unicode test");

        CallableQuery result = callableQuery.setNClob(paramName, value);

        verify(mockCallableStatement).setNClob(paramName, value);
        assertSame(callableQuery, result);
    }

    @Test
    public void testSetNClobReaderWithLength() throws SQLException {
        String paramName = "nclobParam";
        Reader value = new StringReader("unicode test");
        long length = 12L;

        CallableQuery result = callableQuery.setNClob(paramName, value, length);

        verify(mockCallableStatement).setNClob(paramName, value, length);
        assertSame(callableQuery, result);
    }

    @Test
    public void testSetURL() throws SQLException, MalformedURLException {
        String paramName = "urlParam";
        URL value = new URL("http://example.com");

        CallableQuery result = callableQuery.setURL(paramName, value);

        verify(mockCallableStatement).setURL(paramName, value);
        assertSame(callableQuery, result);
    }

    @Test
    public void testSetSQLXML() throws SQLException {
        String paramName = "xmlParam";
        java.sql.SQLXML value = mock(java.sql.SQLXML.class);

        CallableQuery result = callableQuery.setSQLXML(paramName, value);

        verify(mockCallableStatement).setSQLXML(paramName, value);
        assertSame(callableQuery, result);
    }

    @Test
    public void testSetRowId() throws SQLException {
        String paramName = "rowIdParam";
        java.sql.RowId value = mock(java.sql.RowId.class);

        CallableQuery result = callableQuery.setRowId(paramName, value);

        verify(mockCallableStatement).setRowId(paramName, value);
        assertSame(callableQuery, result);
    }

    @Test
    public void testSetObject() throws SQLException {
        String paramName = "objectParam";
        Object value = "test object";

        CallableQuery result = callableQuery.setObject(paramName, value);

        verify(mockCallableStatement).setString(paramName, "test object");
        assertSame(callableQuery, result);
    }

    @Test
    public void testSetObjectNull() throws SQLException {
        String paramName = "objectParam";
        Object value = null;

        CallableQuery result = callableQuery.setObject(paramName, value);

        verify(mockCallableStatement).setObject(paramName, null);
        assertSame(callableQuery, result);
    }

    @Test
    public void testSetObjectWithSqlType() throws SQLException {
        String paramName = "objectParam";
        Object value = "test";
        int sqlType = Types.VARCHAR;

        CallableQuery result = callableQuery.setObject(paramName, value, sqlType);

        verify(mockCallableStatement).setObject(paramName, value, sqlType);
        assertSame(callableQuery, result);
    }

    @Test
    public void testSetObjectWithSqlTypeAndScale() throws SQLException {
        String paramName = "objectParam";
        Object value = new BigDecimal("123.45");
        int sqlType = Types.DECIMAL;
        int scale = 2;

        CallableQuery result = callableQuery.setObject(paramName, value, sqlType, scale);

        verify(mockCallableStatement).setObject(paramName, value, sqlType, scale);
        assertSame(callableQuery, result);
    }

    @Test
    public void testSetParametersMap() throws SQLException {
        Map<String, Object> params = new HashMap<>();
        params.put("param1", "value1");
        params.put("param2", 123);

        CallableQuery result = callableQuery.setParameters(params);

        verify(mockCallableStatement).setString("param1", "value1");
        verify(mockCallableStatement).setInt("param2", 123);
        assertSame(callableQuery, result);
    }

    @Test
    public void testSetParametersMapNull() {
        assertThrows(IllegalArgumentException.class, () -> {
            callableQuery.setParameters((Map<String, ?>) null);
        });
    }

    @Test
    public void testSetParametersEntity() throws SQLException {
        TestEntity entity = new TestEntity();
        entity.setName("John");
        entity.setAge(30);

        List<String> paramNames = Arrays.asList("name", "age");

        CallableQuery result = callableQuery.setParameters(entity, paramNames);

        // Note: This test would need proper entity setup and mock configuration
        assertSame(callableQuery, result);
    }

    @Test
    public void testSetParametersEntityNull() {
        assertThrows(IllegalArgumentException.class, () -> {
            callableQuery.setParameters(null, Arrays.asList("name"));
        });
    }

    @Test
    public void testRegisterOutParameterByIndex() throws SQLException {
        int paramIndex = 1;
        int sqlType = Types.INTEGER;

        CallableQuery result = callableQuery.registerOutParameter(paramIndex, sqlType);

        verify(mockCallableStatement).registerOutParameter(paramIndex, sqlType);
        assertSame(callableQuery, result);
    }

    @Test
    public void testRegisterOutParameterByIndexWithScale() throws SQLException {
        int paramIndex = 1;
        int sqlType = Types.DECIMAL;
        int scale = 2;

        CallableQuery result = callableQuery.registerOutParameter(paramIndex, sqlType, scale);

        verify(mockCallableStatement).registerOutParameter(paramIndex, sqlType, scale);
        assertSame(callableQuery, result);
    }

    @Test
    public void testRegisterOutParameterByIndexWithTypeName() throws SQLException {
        int paramIndex = 1;
        int sqlType = Types.STRUCT;
        String typeName = "MY_TYPE";

        CallableQuery result = callableQuery.registerOutParameter(paramIndex, sqlType, typeName);

        verify(mockCallableStatement).registerOutParameter(paramIndex, sqlType, typeName);
        assertSame(callableQuery, result);
    }

    @Test
    public void testRegisterOutParameterByName() throws SQLException {
        String paramName = "outputParam";
        int sqlType = Types.VARCHAR;

        CallableQuery result = callableQuery.registerOutParameter(paramName, sqlType);

        verify(mockCallableStatement).registerOutParameter(paramName, sqlType);
        assertSame(callableQuery, result);
    }

    @Test
    public void testRegisterOutParameterByNameWithScale() throws SQLException {
        String paramName = "outputParam";
        int sqlType = Types.DECIMAL;
        int scale = 2;

        CallableQuery result = callableQuery.registerOutParameter(paramName, sqlType, scale);

        verify(mockCallableStatement).registerOutParameter(paramName, sqlType, scale);
        assertSame(callableQuery, result);
    }

    @Test
    public void testRegisterOutParameterByNameWithTypeName() throws SQLException {
        String paramName = "outputParam";
        int sqlType = Types.STRUCT;
        String typeName = "MY_TYPE";

        CallableQuery result = callableQuery.registerOutParameter(paramName, sqlType, typeName);

        verify(mockCallableStatement).registerOutParameter(paramName, sqlType, typeName);
        assertSame(callableQuery, result);
    }

    @Test
    public void testRegisterOutParameterWithSQLType() throws SQLException {
        int paramIndex = 1;
        SQLType sqlType = mock(SQLType.class);
        when(sqlType.getVendorTypeNumber()).thenReturn(Types.INTEGER);

        CallableQuery result = callableQuery.registerOutParameter(paramIndex, sqlType);

        verify(mockCallableStatement).registerOutParameter(paramIndex, sqlType);
        assertSame(callableQuery, result);
    }

    @Test
    public void testRegisterOutParameterWithSQLTypeAndScale() throws SQLException {
        int paramIndex = 1;
        SQLType sqlType = mock(SQLType.class);
        when(sqlType.getVendorTypeNumber()).thenReturn(Types.DECIMAL);
        int scale = 2;

        CallableQuery result = callableQuery.registerOutParameter(paramIndex, sqlType, scale);

        verify(mockCallableStatement).registerOutParameter(paramIndex, sqlType, scale);
        assertSame(callableQuery, result);
    }

    @Test
    public void testRegisterOutParameterWithSQLTypeAndTypeName() throws SQLException {
        int paramIndex = 1;
        SQLType sqlType = mock(SQLType.class);
        when(sqlType.getVendorTypeNumber()).thenReturn(Types.STRUCT);
        String typeName = "MY_TYPE";

        CallableQuery result = callableQuery.registerOutParameter(paramIndex, sqlType, typeName);

        verify(mockCallableStatement).registerOutParameter(paramIndex, sqlType, typeName);
        assertSame(callableQuery, result);
    }

    @Test
    public void testRegisterOutParameterByNameWithSQLType() throws SQLException {
        String paramName = "outputParam";
        SQLType sqlType = mock(SQLType.class);
        when(sqlType.getVendorTypeNumber()).thenReturn(Types.VARCHAR);

        CallableQuery result = callableQuery.registerOutParameter(paramName, sqlType);

        verify(mockCallableStatement).registerOutParameter(paramName, sqlType);
        assertSame(callableQuery, result);
    }

    @Test
    public void testRegisterOutParameterByNameWithSQLTypeAndScale() throws SQLException {
        String paramName = "outputParam";
        SQLType sqlType = mock(SQLType.class);
        when(sqlType.getVendorTypeNumber()).thenReturn(Types.DECIMAL);
        int scale = 2;

        CallableQuery result = callableQuery.registerOutParameter(paramName, sqlType, scale);

        verify(mockCallableStatement).registerOutParameter(paramName, sqlType, scale);
        assertSame(callableQuery, result);
    }

    @Test
    public void testRegisterOutParameterByNameWithSQLTypeAndTypeName() throws SQLException {
        String paramName = "outputParam";
        SQLType sqlType = mock(SQLType.class);
        when(sqlType.getVendorTypeNumber()).thenReturn(Types.STRUCT);
        String typeName = "MY_TYPE";

        CallableQuery result = callableQuery.registerOutParameter(paramName, sqlType, typeName);

        verify(mockCallableStatement).registerOutParameter(paramName, sqlType, typeName);
        assertSame(callableQuery, result);
    }

    @Test
    public void testRegisterOutParametersWithSetter() throws SQLException {
        Jdbc.ParametersSetter<CallableQuery> setter = query -> {
            query.registerOutParameter(1, Types.INTEGER);
            query.registerOutParameter(2, Types.VARCHAR);
        };

        CallableQuery result = callableQuery.registerOutParameters(setter);

        verify(mockCallableStatement).registerOutParameter(1, Types.INTEGER);
        verify(mockCallableStatement).registerOutParameter(2, Types.VARCHAR);
        assertSame(callableQuery, result);
    }

    @Test
    public void testRegisterOutParametersWithSetterNull() {
        assertThrows(IllegalArgumentException.class, () -> {
            callableQuery.registerOutParameters((Jdbc.ParametersSetter<? super CallableQuery>) null);
        });
    }

    @Test
    public void testRegisterOutParametersWithBiSetter() throws SQLException {
        String param = "test";
        Jdbc.BiParametersSetter<CallableQuery, String> setter = (query, p) -> {
            query.registerOutParameter(p + "_out", Types.VARCHAR);
        };

        CallableQuery result = callableQuery.registerOutParameters(param, setter);

        verify(mockCallableStatement).registerOutParameter("test_out", Types.VARCHAR);
        assertSame(callableQuery, result);
    }

    @Test
    public void testRegisterOutParametersWithBiSetterNull() {
        assertThrows(IllegalArgumentException.class, () -> {
            callableQuery.registerOutParameters("test", null);
        });
    }

    @Test
    public void testExecuteAndGetOutParameters() throws SQLException {
        when(mockCallableStatement.execute()).thenReturn(true);

        // Since JdbcUtil.getOutParameters is static, we'd need to mock it properly
        // For now, just verify the basic execution
        callableQuery.registerOutParameter(1, Types.INTEGER);

        assertDoesNotThrow(() -> callableQuery.executeAndGetOutParameters());
        verify(mockCallableStatement).execute();
    }

    //    @Test
    //    public void testQueryAndGetOutParameters() throws SQLException {
    //        when(mockCallableStatement.execute()).thenReturn(true);
    //        when(mockCallableStatement.getResultSet()).thenReturn(mockResultSet);
    //        when(mockCallableStatement.getUpdateCount()).thenReturn(-1);
    //
    //        callableQuery.registerOutParameter(1, Types.INTEGER);
    //
    //        // Test would need proper ResultExtractor mock setup
    //        assertDoesNotThrow(() -> callableQuery.queryAndGetOutParameters());
    //    }

    @Test
    public void testQueryAndGetOutParametersWithExtractor() throws SQLException {
        when(mockCallableStatement.execute()).thenReturn(true);
        when(mockCallableStatement.getResultSet()).thenReturn(mockResultSet);
        when(mockCallableStatement.getUpdateCount()).thenReturn(-1);

        Jdbc.ResultExtractor<String> extractor = rs -> "test result";

        callableQuery.registerOutParameter(1, Types.INTEGER);

        // Test would need proper ResultExtractor mock setup
        assertDoesNotThrow(() -> callableQuery.queryAndGetOutParameters(extractor));
    }

    @Test
    public void testQueryAndGetOutParametersWithBiExtractor() throws SQLException {
        when(mockCallableStatement.execute()).thenReturn(true);
        when(mockCallableStatement.getResultSet()).thenReturn(mockResultSet);
        when(mockCallableStatement.getUpdateCount()).thenReturn(-1);

        when(mockResultSet.getMetaData()).thenReturn(mockResultSetMetaData);
        when(mockResultSetMetaData.getColumnCount()).thenReturn(1);
        when(mockResultSetMetaData.getColumnLabel(1)).thenReturn("label");

        Jdbc.BiResultExtractor<String> extractor = (rs, labels) -> "test result";

        callableQuery.registerOutParameter(1, Types.INTEGER);

        // Test would need proper BiResultExtractor mock setup
        assertDoesNotThrow(() -> callableQuery.queryAndGetOutParameters(extractor));
    }

    @Test
    public void testExecuteThenApplyWithFunction() throws SQLException {
        when(mockCallableStatement.execute()).thenReturn(true);

        String result = callableQuery.executeThenApply(stmt -> "executed");

        assertEquals("executed", result);
        verify(mockCallableStatement).execute();
    }

    @Test
    public void testExecuteThenApplyWithBiFunction() throws SQLException {
        when(mockCallableStatement.execute()).thenReturn(true);

        String result = callableQuery.executeThenApply((stmt, isResultSet) -> isResultSet ? "has results" : "no results");

        assertEquals("has results", result);
        verify(mockCallableStatement).execute();
    }

    @Test
    public void testExecuteThenApplyWithTriFunction() throws SQLException {
        when(mockCallableStatement.execute()).thenReturn(false);

        String result = callableQuery.executeThenApply((stmt, outParams, isResultSet) -> "outParams: " + outParams.size() + ", isResultSet: " + isResultSet);

        assertEquals("outParams: 0, isResultSet: false", result);
        verify(mockCallableStatement).execute();
    }

    @Test
    public void testExecuteThenAcceptWithConsumer() throws SQLException {
        when(mockCallableStatement.execute()).thenReturn(true);

        callableQuery.executeThenAccept(stmt -> {
            // Consumer logic
        });

        verify(mockCallableStatement).execute();
    }

    @Test
    public void testExecuteThenAcceptWithBiConsumer() throws SQLException {
        when(mockCallableStatement.execute()).thenReturn(false);

        callableQuery.executeThenAccept((stmt, isResultSet) -> {
            assertFalse(isResultSet);
        });

        verify(mockCallableStatement).execute();
    }

    @Test
    public void testExecuteThenAcceptWithTriConsumer() throws SQLException {
        when(mockCallableStatement.execute()).thenReturn(true);

        callableQuery.executeThenAccept((stmt, outParams, isResultSet) -> {
            assertTrue(isResultSet);
            assertNotNull(outParams);
        });

        verify(mockCallableStatement).execute();
    }

    @Test
    public void testCloseStatement() throws SQLException {
        callableQuery.closeStatement();

        verify(mockCallableStatement).clearParameters();
        verify(mockCallableStatement).close();
    }

    // Helper test entity class
    private static class TestEntity {
        private String name;
        private int age;

        public String getName() {
            return name;
        }

        public void setName(String name) {
            this.name = name;
        }

        public int getAge() {
            return age;
        }

        public void setAge(int age) {
            this.age = age;
        }
    }
}