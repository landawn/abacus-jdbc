package com.landawn.abacus.jdbc;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
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
import java.sql.Array;
import java.sql.Blob;
import java.sql.Clob;
import java.sql.Connection;
import java.sql.JDBCType;
import java.sql.NClob;
import java.sql.PreparedStatement;
import java.sql.Ref;
import java.sql.ResultSet;
import java.sql.RowId;
import java.sql.SQLXML;
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
import java.util.Collection;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import com.landawn.abacus.TestBase;
import com.landawn.abacus.query.ParsedSql;
import com.landawn.abacus.type.Type;
import com.landawn.abacus.type.TypeFactory;
import com.landawn.abacus.util.ImmutableList;

public class NamedQueryTest extends TestBase {

    // TODO: The remaining NamedQuery setter overload matrix mirrors AbstractQuery parameter binding behavior. Add
    // targeted tests only when a stable statement fixture is needed beyond the representative coverage already here.

    @Mock
    private PreparedStatement mockPreparedStatement;

    @Mock
    private Connection mockConnection;

    @Mock
    private ResultSet mockResultSet;

    @Mock
    private ParsedSql mockParsedSql;

    private NamedQuery namedQuery;

    @BeforeEach
    public void setUp() throws Exception {
        MockitoAnnotations.openMocks(this);
        when(mockPreparedStatement.getConnection()).thenReturn(mockConnection);
        when(mockParsedSql.namedParameters()).thenReturn(ImmutableList.of("param1", "param2"));
        when(mockParsedSql.parameterCount()).thenReturn(2);
        when(mockParsedSql.originalSql()).thenReturn("SELECT * FROM table WHERE col1 = :param1 AND col2 = :param2");

        namedQuery = new NamedQuery(mockPreparedStatement, mockParsedSql);
    }

    @Test
    public void testSetNullWithSqlType() throws SQLException {
        String paramName = "param1";
        int sqlType = Types.INTEGER;

        NamedQuery result = namedQuery.setNull(paramName, sqlType);

        verify(mockPreparedStatement).setNull(1, sqlType);
        assertSame(namedQuery, result);
    }

    @Test
    public void testSetNullWithSqlTypeNotFound() throws SQLException {
        String paramName = "nonExistent";
        int sqlType = Types.INTEGER;

        assertThrows(IllegalArgumentException.class, () -> {
            namedQuery.setNull(paramName, sqlType);
        });
    }

    @Test
    public void testSetNullWithSqlTypeAndTypeName() throws SQLException {
        String paramName = "param1";
        int sqlType = Types.STRUCT;
        String typeName = "MY_TYPE";

        NamedQuery result = namedQuery.setNull(paramName, sqlType, typeName);

        verify(mockPreparedStatement).setNull(1, sqlType, typeName);
        assertSame(namedQuery, result);
    }

    @Test
    public void testConstructor_InvalidParameterCount() {
        when(mockParsedSql.namedParameters()).thenReturn(ImmutableList.of("param1"));
        when(mockParsedSql.parameterCount()).thenReturn(2);

        assertThrows(IllegalArgumentException.class, () -> new NamedQuery(mockPreparedStatement, mockParsedSql));
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
    public void testSetIntObjectNull() throws SQLException {
        String paramName = "param1";
        Integer value = null;

        NamedQuery result = namedQuery.setInt(paramName, value);

        verify(mockPreparedStatement).setNull(1, Types.INTEGER);
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
    public void testSetLongObjectNull() throws SQLException {
        String paramName = "param1";
        Long value = null;

        NamedQuery result = namedQuery.setLong(paramName, value);

        verify(mockPreparedStatement).setNull(1, Types.BIGINT);
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
    public void testSetLongBigIntegerNull() throws SQLException {
        String paramName = "param1";
        BigInteger value = null;

        NamedQuery result = namedQuery.setLong(paramName, value);

        verify(mockPreparedStatement).setNull(1, Types.BIGINT);
        assertSame(namedQuery, result);
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
    public void testSetFloatObjectNull() throws SQLException {
        String paramName = "param1";
        Float value = null;

        NamedQuery result = namedQuery.setFloat(paramName, value);

        verify(mockPreparedStatement).setNull(1, Types.FLOAT);
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

    public void testSetBigDecimalBigIntegerNull() throws SQLException {
        String paramName = "param1";
        BigInteger value = null;

        NamedQuery result = namedQuery.setBigDecimal(paramName, value);

        verify(mockPreparedStatement).setNull(1, Types.DECIMAL);
        assertSame(namedQuery, result);
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
    public void testSetStringCharSequenceNull() throws SQLException {
        String paramName = "param1";
        CharSequence value = null;

        NamedQuery result = namedQuery.setString(paramName, value);

        verify(mockPreparedStatement).setString(1, null);
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
    public void testSetStringCharacterNull() throws SQLException {
        String paramName = "param1";
        Character value = null;

        NamedQuery result = namedQuery.setString(paramName, value);

        verify(mockPreparedStatement).setString(1, null);
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

    @Test
    public void testSetDateSqlDate() throws SQLException {
        String paramName = "param1";
        java.sql.Date value = java.sql.Date.valueOf("2023-06-15");

        NamedQuery result = namedQuery.setDate(paramName, value);

        verify(mockPreparedStatement).setDate(1, value);
        assertSame(namedQuery, result);
    }

    @Test
    public void testSetDateUtilDate() throws SQLException {
        String paramName = "param1";
        java.util.Date value = new java.util.Date();

        NamedQuery result = namedQuery.setDate(paramName, value);

        verify(mockPreparedStatement).setDate(eq(1), any(java.sql.Date.class));
        assertSame(namedQuery, result);
    }

    @Test
    public void testSetDateUtilDateNull() throws SQLException {
        String paramName = "param1";
        java.util.Date value = null;

        NamedQuery result = namedQuery.setDate(paramName, value);

        verify(mockPreparedStatement).setDate(1, null);
        assertSame(namedQuery, result);
    }

    @Test
    public void testSetDateLocalDate() throws SQLException {
        String paramName = "param1";
        LocalDate value = LocalDate.of(2023, 6, 15);

        NamedQuery result = namedQuery.setDate(paramName, value);

        verify(mockPreparedStatement).setDate(1, java.sql.Date.valueOf(value));
        assertSame(namedQuery, result);
    }

    @Test
    public void testSetDateLocalDateNull() throws SQLException {
        String paramName = "param1";
        LocalDate value = null;

        NamedQuery result = namedQuery.setDate(paramName, value);

        verify(mockPreparedStatement).setDate(1, null);
        assertSame(namedQuery, result);
    }

    @Test
    public void testSetTimeSqlTime() throws SQLException {
        String paramName = "param1";
        java.sql.Time value = java.sql.Time.valueOf("14:30:00");

        NamedQuery result = namedQuery.setTime(paramName, value);

        verify(mockPreparedStatement).setTime(1, value);
        assertSame(namedQuery, result);
    }

    @Test
    public void testSetTimeUtilDate() throws SQLException {
        String paramName = "param1";
        java.util.Date value = new java.util.Date();

        NamedQuery result = namedQuery.setTime(paramName, value);

        verify(mockPreparedStatement).setTime(eq(1), any(java.sql.Time.class));
        assertSame(namedQuery, result);
    }

    @Test
    public void testSetTimeLocalTime() throws SQLException {
        String paramName = "param1";
        LocalTime value = LocalTime.of(14, 30, 0);

        NamedQuery result = namedQuery.setTime(paramName, value);

        verify(mockPreparedStatement).setTime(1, java.sql.Time.valueOf(value));
        assertSame(namedQuery, result);
    }

    @Test
    public void testSetTimestamp() throws SQLException {
        String paramName = "param1";
        Timestamp value = new Timestamp(System.currentTimeMillis());

        NamedQuery result = namedQuery.setTimestamp(paramName, value);

        verify(mockPreparedStatement).setTimestamp(1, value);
        assertSame(namedQuery, result);
    }

    @Test
    public void testSetTimestampUtilDate() throws SQLException {
        String paramName = "param1";
        java.util.Date value = new java.util.Date();

        NamedQuery result = namedQuery.setTimestamp(paramName, value);

        verify(mockPreparedStatement).setTimestamp(eq(1), any(Timestamp.class));
        assertSame(namedQuery, result);
    }

    @Test
    public void testSetTimestampLocalDateTime() throws SQLException {
        String paramName = "param1";
        LocalDateTime value = LocalDateTime.now();

        NamedQuery result = namedQuery.setTimestamp(paramName, value);

        verify(mockPreparedStatement).setTimestamp(1, Timestamp.valueOf(value));
        assertSame(namedQuery, result);
    }

    @Test
    public void testSetTimestampZonedDateTime() throws SQLException {
        String paramName = "param1";
        ZonedDateTime value = ZonedDateTime.now();

        NamedQuery result = namedQuery.setTimestamp(paramName, value);

        verify(mockPreparedStatement).setTimestamp(1, Timestamp.from(value.toInstant()));
        assertSame(namedQuery, result);
    }

    @Test
    public void testSetTimestampOffsetDateTime() throws SQLException {
        String paramName = "param1";
        OffsetDateTime value = OffsetDateTime.now();

        NamedQuery result = namedQuery.setTimestamp(paramName, value);

        verify(mockPreparedStatement).setTimestamp(1, Timestamp.from(value.toInstant()));
        assertSame(namedQuery, result);
    }

    @Test
    public void testSetTimestampInstant() throws SQLException {
        String paramName = "param1";
        Instant value = Instant.now();

        NamedQuery result = namedQuery.setTimestamp(paramName, value);

        verify(mockPreparedStatement).setTimestamp(1, Timestamp.from(value));
        assertSame(namedQuery, result);
    }

    @Test
    public void testSetBytes() throws SQLException {
        String paramName = "param1";
        byte[] value = new byte[] { 1, 2, 3, 4, 5 };

        NamedQuery result = namedQuery.setBytes(paramName, value);

        verify(mockPreparedStatement).setBytes(1, value);
        assertSame(namedQuery, result);
    }

    @Test
    public void testSetAsciiStream() throws SQLException {
        String paramName = "param1";
        InputStream value = new ByteArrayInputStream("test".getBytes());

        NamedQuery result = namedQuery.setAsciiStream(paramName, value);

        verify(mockPreparedStatement).setAsciiStream(1, value);
        assertSame(namedQuery, result);
    }

    @Test
    public void testSetAsciiStreamWithLength() throws SQLException {
        String paramName = "param1";
        InputStream value = new ByteArrayInputStream("test".getBytes());
        long length = 4L;

        NamedQuery result = namedQuery.setAsciiStream(paramName, value, length);

        verify(mockPreparedStatement).setAsciiStream(1, value, length);
        assertSame(namedQuery, result);
    }

    @Test
    public void testSetBinaryStream() throws SQLException {
        String paramName = "param1";
        InputStream value = new ByteArrayInputStream(new byte[] { 1, 2, 3 });

        NamedQuery result = namedQuery.setBinaryStream(paramName, value);

        verify(mockPreparedStatement).setBinaryStream(1, value);
        assertSame(namedQuery, result);
    }

    @Test
    public void testSetBinaryStreamWithLength() throws SQLException {
        String paramName = "param1";
        InputStream value = new ByteArrayInputStream(new byte[] { 1, 2, 3 });
        long length = 3L;

        NamedQuery result = namedQuery.setBinaryStream(paramName, value, length);

        verify(mockPreparedStatement).setBinaryStream(1, value, length);
        assertSame(namedQuery, result);
    }

    @Test
    public void testSetCharacterStream() throws SQLException {
        String paramName = "param1";
        Reader value = new StringReader("test");

        NamedQuery result = namedQuery.setCharacterStream(paramName, value);

        verify(mockPreparedStatement).setCharacterStream(1, value);
        assertSame(namedQuery, result);
    }

    @Test
    public void testSetCharacterStreamWithLength() throws SQLException {
        String paramName = "param1";
        Reader value = new StringReader("test");
        long length = 4L;

        NamedQuery result = namedQuery.setCharacterStream(paramName, value, length);

        verify(mockPreparedStatement).setCharacterStream(1, value, length);
        assertSame(namedQuery, result);
    }

    @Test
    public void testSetNCharacterStream() throws SQLException {
        String paramName = "param1";
        Reader value = new StringReader("unicode test");

        NamedQuery result = namedQuery.setNCharacterStream(paramName, value);

        verify(mockPreparedStatement).setNCharacterStream(1, value);
        assertSame(namedQuery, result);
    }

    @Test
    public void testSetNCharacterStreamWithLength() throws SQLException {
        String paramName = "param1";
        Reader value = new StringReader("unicode test");
        long length = 12L;

        NamedQuery result = namedQuery.setNCharacterStream(paramName, value, length);

        verify(mockPreparedStatement).setNCharacterStream(1, value, length);
        assertSame(namedQuery, result);
    }

    @Test
    public void testSetBlob() throws SQLException {
        String paramName = "param1";
        java.sql.Blob value = mock(java.sql.Blob.class);

        NamedQuery result = namedQuery.setBlob(paramName, value);

        verify(mockPreparedStatement).setBlob(1, value);
        assertSame(namedQuery, result);
    }

    @Test
    public void testSetBlobInputStream() throws SQLException {
        String paramName = "param1";
        InputStream value = new ByteArrayInputStream(new byte[] { 1, 2, 3 });

        NamedQuery result = namedQuery.setBlob(paramName, value);

        verify(mockPreparedStatement).setBlob(1, value);
        assertSame(namedQuery, result);
    }

    @Test
    public void testSetBlobInputStreamWithLength() throws SQLException {
        String paramName = "param1";
        InputStream value = new ByteArrayInputStream(new byte[] { 1, 2, 3 });
        long length = 3L;

        NamedQuery result = namedQuery.setBlob(paramName, value, length);

        verify(mockPreparedStatement).setBlob(1, value, length);
        assertSame(namedQuery, result);
    }

    @Test
    public void testSetClob() throws SQLException {
        String paramName = "param1";
        java.sql.Clob value = mock(java.sql.Clob.class);

        NamedQuery result = namedQuery.setClob(paramName, value);

        verify(mockPreparedStatement).setClob(1, value);
        assertSame(namedQuery, result);
    }

    @Test
    public void testSetClobReader() throws SQLException {
        String paramName = "param1";
        Reader value = new StringReader("test");

        NamedQuery result = namedQuery.setClob(paramName, value);

        verify(mockPreparedStatement).setClob(1, value);
        assertSame(namedQuery, result);
    }

    @Test
    public void testSetClobReaderWithLength() throws SQLException {
        String paramName = "param1";
        Reader value = new StringReader("test");
        long length = 4L;

        NamedQuery result = namedQuery.setClob(paramName, value, length);

        verify(mockPreparedStatement).setClob(1, value, length);
        assertSame(namedQuery, result);
    }

    @Test
    public void testSetNClob() throws SQLException {
        String paramName = "param1";
        java.sql.NClob value = mock(java.sql.NClob.class);

        NamedQuery result = namedQuery.setNClob(paramName, value);

        verify(mockPreparedStatement).setNClob(1, value);
        assertSame(namedQuery, result);
    }

    @Test
    public void testSetNClobReader() throws SQLException {
        String paramName = "param1";
        Reader value = new StringReader("unicode test");

        NamedQuery result = namedQuery.setNClob(paramName, value);

        verify(mockPreparedStatement).setNClob(1, value);
        assertSame(namedQuery, result);
    }

    @Test
    public void testSetNClobReaderWithLength() throws SQLException {
        String paramName = "param1";
        Reader value = new StringReader("unicode test");
        long length = 12L;

        NamedQuery result = namedQuery.setNClob(paramName, value, length);

        verify(mockPreparedStatement).setNClob(1, value, length);
        assertSame(namedQuery, result);
    }

    @Test
    public void testSetURL() throws SQLException, MalformedURLException {
        String paramName = "param1";
        URL value = new URL("http://example.com");

        NamedQuery result = namedQuery.setURL(paramName, value);

        verify(mockPreparedStatement).setURL(1, value);
        assertSame(namedQuery, result);
    }

    @Test
    public void testSetSQLXML() throws SQLException {
        String paramName = "param1";
        java.sql.SQLXML value = mock(java.sql.SQLXML.class);

        NamedQuery result = namedQuery.setSQLXML(paramName, value);

        verify(mockPreparedStatement).setSQLXML(1, value);
        assertSame(namedQuery, result);
    }

    @Test
    public void testSetRowId() throws SQLException {
        String paramName = "param1";
        java.sql.RowId value = mock(java.sql.RowId.class);

        NamedQuery result = namedQuery.setRowId(paramName, value);

        verify(mockPreparedStatement).setRowId(1, value);
        assertSame(namedQuery, result);
    }

    @Test
    public void testSetRef() throws SQLException {
        String paramName = "param1";
        java.sql.Ref value = mock(java.sql.Ref.class);

        NamedQuery result = namedQuery.setRef(paramName, value);

        verify(mockPreparedStatement).setRef(1, value);
        assertSame(namedQuery, result);
    }

    @Test
    public void testSetArray() throws SQLException {
        String paramName = "param1";
        java.sql.Array value = mock(java.sql.Array.class);

        NamedQuery result = namedQuery.setArray(paramName, value);

        verify(mockPreparedStatement).setArray(1, value);
        assertSame(namedQuery, result);
    }

    @Test
    public void testSetObject() throws SQLException {
        String paramName = "param1";
        Object value = "test object";

        NamedQuery result = namedQuery.setObject(paramName, value);

        verify(mockPreparedStatement).setString(1, "test object");
        assertSame(namedQuery, result);
    }

    @Test
    public void testSetObjectNull() throws SQLException {
        String paramName = "param1";
        Object value = null;

        NamedQuery result = namedQuery.setObject(paramName, value);

        verify(mockPreparedStatement).setObject(1, null);
        assertSame(namedQuery, result);
    }

    @Test
    public void testSetObjectWithSqlType() throws SQLException {
        String paramName = "param1";
        Object value = "test";
        int sqlType = Types.VARCHAR;

        NamedQuery result = namedQuery.setObject(paramName, value, sqlType);

        verify(mockPreparedStatement).setObject(1, value, sqlType);
        assertSame(namedQuery, result);
    }

    @Test
    public void testSetObjectWithSqlTypeAndScale() throws SQLException {
        String paramName = "param1";
        Object value = new BigDecimal("123.45");
        int sqlType = Types.DECIMAL;
        int scale = 2;

        NamedQuery result = namedQuery.setObject(paramName, value, sqlType, scale);

        verify(mockPreparedStatement).setObject(1, value, sqlType, scale);
        assertSame(namedQuery, result);
    }

    @Test
    public void testSetObjectWithSQLType() throws SQLException {
        String paramName = "param1";
        Object value = "test";
        SQLType sqlType = mock(SQLType.class);

        NamedQuery result = namedQuery.setObject(paramName, value, sqlType);

        verify(mockPreparedStatement).setObject(1, value, sqlType);
        assertSame(namedQuery, result);
    }

    @Test
    public void testSetObjectWithSQLTypeAndScale() throws SQLException {
        String paramName = "param1";
        Object value = new BigDecimal("123.45");
        SQLType sqlType = mock(SQLType.class);
        int scale = 2;

        NamedQuery result = namedQuery.setObject(paramName, value, sqlType, scale);

        verify(mockPreparedStatement).setObject(1, value, sqlType, scale);
        assertSame(namedQuery, result);
    }

    @Test
    public void testSetObjectWithType() throws SQLException {
        String paramName = "param1";
        String value = "test";
        Type<String> type = TypeFactory.getType(String.class);

        NamedQuery result = namedQuery.setObject(paramName, value, type);

        // Type would handle the actual setting
        assertSame(namedQuery, result);
    }

    @Test
    public void testSetParametersMap() throws SQLException {
        Map<String, Object> params = new HashMap<>();
        params.put("param1", "value1");
        params.put("param2", 123);

        NamedQuery result = namedQuery.setParameters(params);

        verify(mockPreparedStatement).setString(1, "value1");
        verify(mockPreparedStatement).setInt(2, 123);
        assertSame(namedQuery, result);
    }

    @Test
    public void testSetParametersMapNull() {
        assertThrows(IllegalArgumentException.class, () -> {
            namedQuery.setParameters((Map<String, ?>) null);
        });
    }

    @Test
    public void testSetParametersObject() throws SQLException {
        TestEntity entity = new TestEntity();
        entity.setParam1("value1");
        entity.setParam2(123);

        NamedQuery result = namedQuery.setParameters(entity);

        // Would need proper mock setup for entity parsing
        assertSame(namedQuery, result);
    }

    @Test
    public void testSetParametersObjectNull() {
        assertThrows(IllegalArgumentException.class, () -> {
            namedQuery.setParameters((Object) null);
        });
    }

    @Test
    public void testSetParametersEntityWithNames() throws SQLException {
        TestEntity entity = new TestEntity();
        entity.setParam1("value1");
        entity.setParam2(123);

        Collection<String> paramNames = Arrays.asList("param1");

        NamedQuery result = namedQuery.setParameters(entity, paramNames);

        // Would need proper mock setup for entity parsing
        assertSame(namedQuery, result);
    }

    @Test
    public void testSetParametersEntityWithNamesNull() {
        assertThrows(IllegalArgumentException.class, () -> {
            namedQuery.setParameters(null, Arrays.asList("param1"));
        });
    }

    @Test
    public void testSetParametersWithSetter() throws SQLException {
        String param = "test";
        Jdbc.TriParametersSetter<NamedQuery, String> setter = (sql, query, p) -> {
            query.setString("param1", p);
        };

        NamedQuery result = namedQuery.setParameters(param, setter);

        verify(mockPreparedStatement).setString(1, "test");
        assertSame(namedQuery, result);
    }

    @Test
    public void testSetParametersWithSetterNull() {
        assertThrows(IllegalArgumentException.class, () -> {
            namedQuery.setParameters("test", (Collection<String>) null);
        });
    }

    @Test
    public void testAddBatchParametersCollection() throws SQLException {
        List<Map<String, Object>> batchParams = new ArrayList<>();
        batchParams.add(Map.of("param1", "value1", "param2", 100));
        batchParams.add(Map.of("param1", "value2", "param2", 200));

        NamedQuery result = namedQuery.addBatchParameters(batchParams);

        verify(mockPreparedStatement, times(2)).setString(anyInt(), anyString());
        verify(mockPreparedStatement, times(2)).setInt(anyInt(), anyInt());
        verify(mockPreparedStatement, times(2)).addBatch();
        assertSame(namedQuery, result);
    }

    @Test
    public void testAddBatchParametersCollectionNull() {
        assertThrows(IllegalArgumentException.class, () -> {
            namedQuery.addBatchParameters((Collection<?>) null);
        });
    }

    @Test
    public void testAddBatchParametersIterator() throws SQLException {
        List<Map<String, Object>> batchParams = new ArrayList<>();
        batchParams.add(Map.of("param1", "value1", "param2", 100));
        batchParams.add(Map.of("param1", "value2", "param2", 200));

        NamedQuery result = namedQuery.addBatchParameters(batchParams.iterator());

        verify(mockPreparedStatement, times(2)).setString(anyInt(), anyString());
        verify(mockPreparedStatement, times(2)).setInt(anyInt(), anyInt());
        verify(mockPreparedStatement, times(2)).addBatch();
        assertSame(namedQuery, result);
    }

    @Test
    public void testAddBatchParametersIteratorNull() {
        assertThrows(IllegalArgumentException.class, () -> {
            namedQuery.addBatchParameters((Iterator<?>) null);
        });
    }

    @Test
    public void testMultipleFrequencyOfParameter() throws SQLException {
        // Create a query with duplicate parameter names
        when(mockParsedSql.namedParameters()).thenReturn(ImmutableList.of("id", "id", "name"));
        when(mockParsedSql.parameterCount()).thenReturn(3);
        namedQuery = new NamedQuery(mockPreparedStatement, mockParsedSql);

        NamedQuery result = namedQuery.setInt("id", 123);

        // Both frequency of "id" should be set
        verify(mockPreparedStatement).setInt(1, 123);
        verify(mockPreparedStatement).setInt(2, 123);
        assertSame(namedQuery, result);
    }

    @Test
    public void testParameterNotFound() {
        assertThrows(IllegalArgumentException.class, () -> {
            namedQuery.setString("nonExistentParam", "value");
        });
    }

    // Tests for the index-by-map code path (parameterCount >= MIN_PARAMETER_COUNT_FOR_INDEX_BY_MAP = 5)
    // These exercise the else branch that uses paramNameIndexMap instead of linear scan

    private NamedQuery createManyParamQuery() throws Exception {
        // 5 parameters triggers index-by-map path
        when(mockParsedSql.namedParameters()).thenReturn(ImmutableList.of("a", "b", "c", "d", "e"));
        when(mockParsedSql.parameterCount()).thenReturn(5);
        when(mockParsedSql.originalSql()).thenReturn("SELECT * FROM t WHERE a=:a AND b=:b AND c=:c AND d=:d AND e=:e");
        return new NamedQuery(mockPreparedStatement, mockParsedSql);
    }

    @Test
    public void testSetNull_MapPath() throws Exception {
        NamedQuery q = createManyParamQuery();
        NamedQuery result = q.setNull("a", Types.INTEGER);
        verify(mockPreparedStatement).setNull(1, Types.INTEGER);
        assertSame(q, result);
    }

    @Test
    public void testSetNullWithTypeName_MapPath() throws Exception {
        NamedQuery q = createManyParamQuery();
        NamedQuery result = q.setNull("b", Types.STRUCT, "MY_TYPE");
        verify(mockPreparedStatement).setNull(2, Types.STRUCT, "MY_TYPE");
        assertSame(q, result);
    }

    @Test
    public void testSetNull_MapPath_NotFound() throws Exception {
        NamedQuery q = createManyParamQuery();
        assertThrows(IllegalArgumentException.class, () -> q.setNull("unknown", Types.INTEGER));
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

    @Test
    public void testSetLong_MapPath() throws Exception {
        NamedQuery q = createManyParamQuery();
        NamedQuery result = q.setLong("e", 100L);
        verify(mockPreparedStatement).setLong(5, 100L);
        assertSame(q, result);
    }

    @Test
    public void testSetFloat_MapPath() throws Exception {
        NamedQuery q = createManyParamQuery();
        NamedQuery result = q.setFloat("a", 1.5f);
        verify(mockPreparedStatement).setFloat(1, 1.5f);
        assertSame(q, result);
    }

    @Test
    public void testSetDouble_MapPath() throws Exception {
        NamedQuery q = createManyParamQuery();
        NamedQuery result = q.setDouble("b", 3.14);
        verify(mockPreparedStatement).setDouble(2, 3.14);
        assertSame(q, result);
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

    // Test quad duplicate params in index-by-map path (size>=4, uses for loop)
    @Test
    public void testSetNull_MapPath_QuadDuplicateParam() throws Exception {
        when(mockParsedSql.namedParameters()).thenReturn(ImmutableList.of("a", "a", "a", "a", "b"));
        when(mockParsedSql.parameterCount()).thenReturn(5);
        when(mockParsedSql.originalSql()).thenReturn("SELECT 1");
        NamedQuery q = new NamedQuery(mockPreparedStatement, mockParsedSql);
        q.setNull("a", Types.VARCHAR);
        verify(mockPreparedStatement, times(4)).setNull(anyInt(), eq(Types.VARCHAR));
    }

    // Test addBatchParameters with null element
    @Test
    public void testAddBatchParameters_NullElement() throws Exception {
        when(mockParsedSql.namedParameters()).thenReturn(ImmutableList.of("a"));
        when(mockParsedSql.parameterCount()).thenReturn(1);
        when(mockParsedSql.originalSql()).thenReturn("SELECT :a");
        NamedQuery q = new NamedQuery(mockPreparedStatement, mockParsedSql);
        List<Object> batch = new ArrayList<>();
        batch.add(null);
        q.addBatchParameters(batch.iterator());
        verify(mockPreparedStatement).setObject(1, null);
        verify(mockPreparedStatement).addBatch();
    }

    // Test addBatchParameters with Collection elements
    @Test
    public void testAddBatchParameters_CollectionElements() throws Exception {
        when(mockParsedSql.namedParameters()).thenReturn(ImmutableList.of("a", "b"));
        when(mockParsedSql.parameterCount()).thenReturn(2);
        when(mockParsedSql.originalSql()).thenReturn("SELECT :a, :b");
        NamedQuery q = new NamedQuery(mockPreparedStatement, mockParsedSql);
        List<Collection<?>> batch = new ArrayList<>();
        batch.add(Arrays.asList("val1", "val2"));
        batch.add(Arrays.asList("val3", "val4"));
        q.addBatchParameters(batch.iterator());
        verify(mockPreparedStatement, times(2)).addBatch();
    }

    // Test addBatchParameters with Object[] elements
    @Test
    public void testAddBatchParameters_ArrayElements() throws Exception {
        when(mockParsedSql.namedParameters()).thenReturn(ImmutableList.of("a", "b"));
        when(mockParsedSql.parameterCount()).thenReturn(2);
        when(mockParsedSql.originalSql()).thenReturn("SELECT :a, :b");
        NamedQuery q = new NamedQuery(mockPreparedStatement, mockParsedSql);
        List<Object[]> batch = new ArrayList<>();
        batch.add(new Object[] { "v1", 1 });
        batch.add(new Object[] { "v2", 2 });
        q.addBatchParameters(batch.iterator());
        verify(mockPreparedStatement, times(2)).addBatch();
    }

    // Helper to create a NamedQuery with paramCount >= MIN_PARAMETER_COUNT_FOR_INDEX_BY_MAP (5)
    // so the paramNameIndexMap branch is exercised.
    private NamedQuery createMapBranchQuery(final ImmutableList<String> names) throws SQLException {
        when(mockParsedSql.namedParameters()).thenReturn(names);
        when(mockParsedSql.parameterCount()).thenReturn(names.size());
        return new NamedQuery(mockPreparedStatement, mockParsedSql);
    }

    @Test
    public void testSetNull_MapBranch_ParamNotFound() throws SQLException {
        // paramCount >= 5, param not in map → throws
        NamedQuery q = createMapBranchQuery(ImmutableList.of("a", "b", "c", "d", "e"));
        assertThrows(IllegalArgumentException.class, () -> q.setNull("unknown", Types.INTEGER));
    }

    @Test
    public void testSetNull_MapBranch_TwoOccurrences() throws SQLException {
        // paramCount >= 5, same param twice → lines 173-174
        NamedQuery q = createMapBranchQuery(ImmutableList.of("a", "b", "c", "p", "p"));
        q.setNull("p", Types.INTEGER);
        verify(mockPreparedStatement).setNull(4, Types.INTEGER);
        verify(mockPreparedStatement).setNull(5, Types.INTEGER);
    }

    @Test
    public void testSetNull_MapBranch_ThreeOccurrences() throws SQLException {
        // paramCount >= 5, same param three times → lines 176-178
        NamedQuery q = createMapBranchQuery(ImmutableList.of("a", "b", "c", "p", "p", "p"));
        q.setNull("p", Types.INTEGER);
        verify(mockPreparedStatement, times(3)).setNull(anyInt(), eq(Types.INTEGER));
    }

    @Test
    public void testSetNull_MapBranch_FourOccurrences() throws SQLException {
        // paramCount >= 5, same param four times → for loop (line 180-182)
        NamedQuery q = createMapBranchQuery(ImmutableList.of("a", "p", "p", "p", "p"));
        q.setNull("p", Types.INTEGER);
        verify(mockPreparedStatement, times(4)).setNull(anyInt(), eq(Types.INTEGER));
    }

    @Test
    public void testSetNullTypeName_LoopBranch_ParamNotFound() throws SQLException {
        // paramCount < 5, typeName overload, param not found → lines 221-222
        assertThrows(IllegalArgumentException.class, () -> namedQuery.setNull("unknown", Types.STRUCT, "MY_TYPE"));
    }

    @Test
    public void testSetNullTypeName_MapBranch_ParamNotFound() throws SQLException {
        // paramCount >= 5, typeName overload, param not found → lines 232-233
        NamedQuery q = createMapBranchQuery(ImmutableList.of("a", "b", "c", "d", "e"));
        assertThrows(IllegalArgumentException.class, () -> q.setNull("unknown", Types.STRUCT, "MY_TYPE"));
    }

    @Test
    public void testSetNullTypeName_MapBranch_TwoOccurrences() throws SQLException {
        // paramCount >= 5, typeName overload, 2 occurrences → lines 238-239
        NamedQuery q = createMapBranchQuery(ImmutableList.of("a", "b", "c", "p", "p"));
        q.setNull("p", Types.STRUCT, "MY_TYPE");
        verify(mockPreparedStatement).setNull(4, Types.STRUCT, "MY_TYPE");
        verify(mockPreparedStatement).setNull(5, Types.STRUCT, "MY_TYPE");
    }

    @Test
    public void testSetNullTypeName_MapBranch_ThreeOccurrences() throws SQLException {
        // paramCount >= 5, typeName overload, 3 occurrences → lines 241-244
        NamedQuery q = createMapBranchQuery(ImmutableList.of("a", "b", "c", "p", "p", "p"));
        q.setNull("p", Types.STRUCT, "MY_TYPE");
        verify(mockPreparedStatement, times(3)).setNull(anyInt(), eq(Types.STRUCT), eq("MY_TYPE"));
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
    public void testSetByte_LoopBranch_ParamNotFound() throws SQLException {
        // paramCount < 5, setByte, param not found → lines 371-372
        assertThrows(IllegalArgumentException.class, () -> namedQuery.setByte("unknown", (byte) 1));
    }

    @Test
    public void testSetShort_LoopBranch_ParamNotFound() throws SQLException {
        // paramCount < 5, setShort, param not found → lines 459-460
        assertThrows(IllegalArgumentException.class, () -> namedQuery.setShort("unknown", (short) 1));
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
    public void testSetBigDecimal_BigIntegerNull_DelegatesToSetNull() throws SQLException {
        namedQuery.setBigDecimal("param1", (BigInteger) null);
        verify(mockPreparedStatement).setNull(1, Types.DECIMAL);
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
        assertThrows(IllegalArgumentException.class,
                () -> namedQuery.setNString("unknown", (CharSequence) new StringBuilder("val")));
    }

    @Test
    public void testSetNStringCharSeq_MapBranch_NotFound() throws SQLException {
        NamedQuery q = createMapBranchQuery(ImmutableList.of("a", "b", "c", "d", "e"));
        assertThrows(IllegalArgumentException.class,
                () -> q.setNString("unknown", (CharSequence) new StringBuilder("val")));
    }

    @Test
    public void testSetNStringCharSeq_MapBranch_OneOccurrence() throws SQLException {
        NamedQuery q = createMapBranchQuery(ImmutableList.of("a", "b", "c", "d", "e"));
        q.setNString("a", (CharSequence) new StringBuilder("hi"));
        verify(mockPreparedStatement).setNString(1, "hi");
    }

    @Test
    public void testSetNStringCharSeq_MapBranch_TwoOccurrences() throws SQLException {
        NamedQuery q = createMapBranchQuery(ImmutableList.of("a", "b", "c", "p", "p"));
        q.setNString("p", (CharSequence) new StringBuilder("two"));
        verify(mockPreparedStatement, times(2)).setNString(anyInt(), eq("two"));
    }

    @Test
    public void testSetNStringCharSeq_MapBranch_ThreeOccurrences() throws SQLException {
        NamedQuery q = createMapBranchQuery(ImmutableList.of("a", "b", "c", "p", "p", "p"));
        q.setNString("p", (CharSequence) new StringBuilder("three"));
        verify(mockPreparedStatement, times(3)).setNString(anyInt(), eq("three"));
    }

    @Test
    public void testSetNStringCharSeq_MapBranch_FourOccurrences() throws SQLException {
        NamedQuery q = createMapBranchQuery(ImmutableList.of("a", "p", "p", "p", "p"));
        q.setNString("p", (CharSequence) new StringBuilder("four"));
        verify(mockPreparedStatement, times(4)).setNString(anyInt(), eq("four"));
    }

    // --- setDate(sql.Date): all paths ---

    @Test
    public void testSetDate_LoopBranch_ParamNotFound() throws SQLException {
        assertThrows(IllegalArgumentException.class,
                () -> namedQuery.setDate("unknown", java.sql.Date.valueOf("2024-01-01")));
    }

    @Test
    public void testSetDate_MapBranch_NotFound() throws SQLException {
        NamedQuery q = createMapBranchQuery(ImmutableList.of("a", "b", "c", "d", "e"));
        assertThrows(IllegalArgumentException.class,
                () -> q.setDate("unknown", java.sql.Date.valueOf("2024-01-01")));
    }

    @Test
    public void testSetDate_MapBranch_OneOccurrence() throws SQLException {
        java.sql.Date d = java.sql.Date.valueOf("2024-06-01");
        NamedQuery q = createMapBranchQuery(ImmutableList.of("a", "b", "c", "d", "e"));
        q.setDate("c", d);
        verify(mockPreparedStatement).setDate(3, d);
    }

    @Test
    public void testSetDate_MapBranch_TwoOccurrences() throws SQLException {
        java.sql.Date d = java.sql.Date.valueOf("2024-06-01");
        NamedQuery q = createMapBranchQuery(ImmutableList.of("a", "b", "c", "p", "p"));
        q.setDate("p", d);
        verify(mockPreparedStatement, times(2)).setDate(anyInt(), eq(d));
    }

    @Test
    public void testSetDate_MapBranch_ThreeOccurrences() throws SQLException {
        java.sql.Date d = java.sql.Date.valueOf("2024-06-01");
        NamedQuery q = createMapBranchQuery(ImmutableList.of("a", "b", "c", "p", "p", "p"));
        q.setDate("p", d);
        verify(mockPreparedStatement, times(3)).setDate(anyInt(), eq(d));
    }

    @Test
    public void testSetDate_MapBranch_FourOccurrences() throws SQLException {
        java.sql.Date d = java.sql.Date.valueOf("2024-06-01");
        NamedQuery q = createMapBranchQuery(ImmutableList.of("a", "p", "p", "p", "p"));
        q.setDate("p", d);
        verify(mockPreparedStatement, times(4)).setDate(anyInt(), eq(d));
    }

    // --- setTime(sql.Time): all paths ---

    @Test
    public void testSetTime_LoopBranch_ParamNotFound() throws SQLException {
        assertThrows(IllegalArgumentException.class,
                () -> namedQuery.setTime("unknown", java.sql.Time.valueOf("10:00:00")));
    }

    @Test
    public void testSetTime_MapBranch_NotFound() throws SQLException {
        NamedQuery q = createMapBranchQuery(ImmutableList.of("a", "b", "c", "d", "e"));
        assertThrows(IllegalArgumentException.class,
                () -> q.setTime("unknown", java.sql.Time.valueOf("10:00:00")));
    }

    @Test
    public void testSetTime_MapBranch_OneOccurrence() throws SQLException {
        java.sql.Time t = java.sql.Time.valueOf("12:30:00");
        NamedQuery q = createMapBranchQuery(ImmutableList.of("a", "b", "c", "d", "e"));
        q.setTime("a", t);
        verify(mockPreparedStatement).setTime(1, t);
    }

    @Test
    public void testSetTime_MapBranch_TwoOccurrences() throws SQLException {
        java.sql.Time t = java.sql.Time.valueOf("09:00:00");
        NamedQuery q = createMapBranchQuery(ImmutableList.of("a", "b", "c", "p", "p"));
        q.setTime("p", t);
        verify(mockPreparedStatement, times(2)).setTime(anyInt(), eq(t));
    }

    @Test
    public void testSetTime_MapBranch_ThreeOccurrences() throws SQLException {
        java.sql.Time t = java.sql.Time.valueOf("15:00:00");
        NamedQuery q = createMapBranchQuery(ImmutableList.of("a", "b", "c", "p", "p", "p"));
        q.setTime("p", t);
        verify(mockPreparedStatement, times(3)).setTime(anyInt(), eq(t));
    }

    @Test
    public void testSetTime_MapBranch_FourOccurrences() throws SQLException {
        java.sql.Time t = java.sql.Time.valueOf("18:00:00");
        NamedQuery q = createMapBranchQuery(ImmutableList.of("a", "p", "p", "p", "p"));
        q.setTime("p", t);
        verify(mockPreparedStatement, times(4)).setTime(anyInt(), eq(t));
    }

    // --- setTimestamp(sql.Timestamp): all paths ---

    @Test
    public void testSetTimestamp_LoopBranch_ParamNotFound() throws SQLException {
        assertThrows(IllegalArgumentException.class,
                () -> namedQuery.setTimestamp("unknown", new Timestamp(0)));
    }

    @Test
    public void testSetTimestamp_MapBranch_NotFound() throws SQLException {
        NamedQuery q = createMapBranchQuery(ImmutableList.of("a", "b", "c", "d", "e"));
        assertThrows(IllegalArgumentException.class, () -> q.setTimestamp("unknown", new Timestamp(0)));
    }

    @Test
    public void testSetTimestamp_MapBranch_OneOccurrence() throws SQLException {
        Timestamp ts = new Timestamp(1000L);
        NamedQuery q = createMapBranchQuery(ImmutableList.of("a", "b", "c", "d", "e"));
        q.setTimestamp("b", ts);
        verify(mockPreparedStatement).setTimestamp(2, ts);
    }

    @Test
    public void testSetTimestamp_MapBranch_TwoOccurrences() throws SQLException {
        Timestamp ts = new Timestamp(2000L);
        NamedQuery q = createMapBranchQuery(ImmutableList.of("a", "b", "c", "p", "p"));
        q.setTimestamp("p", ts);
        verify(mockPreparedStatement, times(2)).setTimestamp(anyInt(), eq(ts));
    }

    @Test
    public void testSetTimestamp_MapBranch_ThreeOccurrences() throws SQLException {
        Timestamp ts = new Timestamp(3000L);
        NamedQuery q = createMapBranchQuery(ImmutableList.of("a", "b", "c", "p", "p", "p"));
        q.setTimestamp("p", ts);
        verify(mockPreparedStatement, times(3)).setTimestamp(anyInt(), eq(ts));
    }

    @Test
    public void testSetTimestamp_MapBranch_FourOccurrences() throws SQLException {
        Timestamp ts = new Timestamp(4000L);
        NamedQuery q = createMapBranchQuery(ImmutableList.of("a", "p", "p", "p", "p"));
        q.setTimestamp("p", ts);
        verify(mockPreparedStatement, times(4)).setTimestamp(anyInt(), eq(ts));
    }

    // --- setBytes: all paths ---

    @Test
    public void testSetBytes_LoopBranch_ParamNotFound() throws SQLException {
        assertThrows(IllegalArgumentException.class,
                () -> namedQuery.setBytes("unknown", new byte[] { 1, 2 }));
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

    // --- setAsciiStream (no-length overload): loop not-found, map not-found, 1-occ ---

    @Test
    public void testSetAsciiStream_LoopBranch_ParamNotFound() throws SQLException {
        assertThrows(IllegalArgumentException.class,
                () -> namedQuery.setAsciiStream("unknown", new ByteArrayInputStream(new byte[] {})));
    }

    @Test
    public void testSetAsciiStream_MapBranch_NotFound() throws SQLException {
        NamedQuery q = createMapBranchQuery(ImmutableList.of("a", "b", "c", "d", "e"));
        assertThrows(IllegalArgumentException.class,
                () -> q.setAsciiStream("unknown", new ByteArrayInputStream(new byte[] {})));
    }

    // --- setBinaryStream (no-length overload): loop not-found, map not-found ---

    @Test
    public void testSetBinaryStream_LoopBranch_ParamNotFound() throws SQLException {
        assertThrows(IllegalArgumentException.class,
                () -> namedQuery.setBinaryStream("unknown", new ByteArrayInputStream(new byte[] {})));
    }

    @Test
    public void testSetBinaryStream_MapBranch_NotFound() throws SQLException {
        NamedQuery q = createMapBranchQuery(ImmutableList.of("a", "b", "c", "d", "e"));
        assertThrows(IllegalArgumentException.class,
                () -> q.setBinaryStream("unknown", new ByteArrayInputStream(new byte[] {})));
    }

    // --- setCharacterStream (no-length overload): loop not-found, map not-found ---

    @Test
    public void testSetCharacterStream_LoopBranch_ParamNotFound() throws SQLException {
        assertThrows(IllegalArgumentException.class,
                () -> namedQuery.setCharacterStream("unknown", new StringReader("x")));
    }

    @Test
    public void testSetCharacterStream_MapBranch_NotFound() throws SQLException {
        NamedQuery q = createMapBranchQuery(ImmutableList.of("a", "b", "c", "d", "e"));
        assertThrows(IllegalArgumentException.class,
                () -> q.setCharacterStream("unknown", new StringReader("x")));
    }

    // --- setNCharacterStream (no-length overload): loop not-found, map not-found ---

    @Test
    public void testSetNCharacterStream_LoopBranch_ParamNotFound() throws SQLException {
        assertThrows(IllegalArgumentException.class,
                () -> namedQuery.setNCharacterStream("unknown", new StringReader("x")));
    }

    @Test
    public void testSetNCharacterStream_MapBranch_NotFound() throws SQLException {
        NamedQuery q = createMapBranchQuery(ImmutableList.of("a", "b", "c", "d", "e"));
        assertThrows(IllegalArgumentException.class,
                () -> q.setNCharacterStream("unknown", new StringReader("x")));
    }

    // --- setNCharacterStream(Reader) map happy paths ---

    @Test
    public void testSetNCharacterStream_MapBranch_OneOccurrence() throws SQLException {
        NamedQuery q = createMapBranchQuery(ImmutableList.of("a", "b", "c", "d", "e"));
        q.setNCharacterStream("a", new StringReader("x"));
        verify(mockPreparedStatement).setNCharacterStream(eq(1), any(Reader.class));
    }

    @Test
    public void testSetNCharacterStream_MapBranch_TwoOccurrences() throws SQLException {
        NamedQuery q = createMapBranchQuery(ImmutableList.of("a", "b", "c", "p", "p"));
        q.setNCharacterStream("p", new StringReader("x"));
        verify(mockPreparedStatement, times(2)).setNCharacterStream(anyInt(), any(Reader.class));
    }

    @Test
    public void testSetNCharacterStream_MapBranch_ThreeOccurrences() throws SQLException {
        NamedQuery q = createMapBranchQuery(ImmutableList.of("a", "b", "c", "p", "p", "p"));
        q.setNCharacterStream("p", new StringReader("x"));
        verify(mockPreparedStatement, times(3)).setNCharacterStream(anyInt(), any(Reader.class));
    }

    @Test
    public void testSetNCharacterStream_MapBranch_FourOccurrences() throws SQLException {
        NamedQuery q = createMapBranchQuery(ImmutableList.of("a", "p", "p", "p", "p"));
        q.setNCharacterStream("p", new StringReader("x"));
        verify(mockPreparedStatement, times(4)).setNCharacterStream(anyInt(), any(Reader.class));
    }

    // --- setNCharacterStream(Reader, long) all paths ---

    @Test
    public void testSetNCharacterStreamLong_LoopBranch_ParamNotFound() throws SQLException {
        assertThrows(IllegalArgumentException.class,
                () -> namedQuery.setNCharacterStream("unknown", new StringReader("x"), 1L));
    }

    @Test
    public void testSetNCharacterStreamLong_MapBranch_NotFound() throws SQLException {
        NamedQuery q = createMapBranchQuery(ImmutableList.of("a", "b", "c", "d", "e"));
        assertThrows(IllegalArgumentException.class,
                () -> q.setNCharacterStream("unknown", new StringReader("x"), 1L));
    }

    @Test
    public void testSetNCharacterStreamLong_MapBranch_OneOccurrence() throws SQLException {
        NamedQuery q = createMapBranchQuery(ImmutableList.of("a", "b", "c", "d", "e"));
        q.setNCharacterStream("b", new StringReader("hi"), 2L);
        verify(mockPreparedStatement).setNCharacterStream(eq(2), any(Reader.class), eq(2L));
    }

    @Test
    public void testSetNCharacterStreamLong_MapBranch_TwoOccurrences() throws SQLException {
        NamedQuery q = createMapBranchQuery(ImmutableList.of("a", "b", "c", "p", "p"));
        q.setNCharacterStream("p", new StringReader("x"), 1L);
        verify(mockPreparedStatement, times(2)).setNCharacterStream(anyInt(), any(Reader.class), eq(1L));
    }

    @Test
    public void testSetNCharacterStreamLong_MapBranch_ThreeOccurrences() throws SQLException {
        NamedQuery q = createMapBranchQuery(ImmutableList.of("a", "b", "c", "p", "p", "p"));
        q.setNCharacterStream("p", new StringReader("x"), 1L);
        verify(mockPreparedStatement, times(3)).setNCharacterStream(anyInt(), any(Reader.class), eq(1L));
    }

    @Test
    public void testSetNCharacterStreamLong_MapBranch_FourOccurrences() throws SQLException {
        NamedQuery q = createMapBranchQuery(ImmutableList.of("a", "p", "p", "p", "p"));
        q.setNCharacterStream("p", new StringReader("x"), 1L);
        verify(mockPreparedStatement, times(4)).setNCharacterStream(anyInt(), any(Reader.class), eq(1L));
    }

    // --- setAsciiStream(InputStream) map happy paths ---

    @Test
    public void testSetAsciiStream_MapBranch_OneOccurrence() throws SQLException {
        NamedQuery q = createMapBranchQuery(ImmutableList.of("a", "b", "c", "d", "e"));
        q.setAsciiStream("c", new ByteArrayInputStream(new byte[]{1}));
        verify(mockPreparedStatement).setAsciiStream(eq(3), any(InputStream.class));
    }

    @Test
    public void testSetAsciiStream_MapBranch_TwoOccurrences() throws SQLException {
        NamedQuery q = createMapBranchQuery(ImmutableList.of("a", "b", "c", "p", "p"));
        q.setAsciiStream("p", new ByteArrayInputStream(new byte[]{1}));
        verify(mockPreparedStatement, times(2)).setAsciiStream(anyInt(), any(InputStream.class));
    }

    @Test
    public void testSetAsciiStream_MapBranch_ThreeOccurrences() throws SQLException {
        NamedQuery q = createMapBranchQuery(ImmutableList.of("a", "b", "c", "p", "p", "p"));
        q.setAsciiStream("p", new ByteArrayInputStream(new byte[]{1}));
        verify(mockPreparedStatement, times(3)).setAsciiStream(anyInt(), any(InputStream.class));
    }

    @Test
    public void testSetAsciiStream_MapBranch_FourOccurrences() throws SQLException {
        NamedQuery q = createMapBranchQuery(ImmutableList.of("a", "p", "p", "p", "p"));
        q.setAsciiStream("p", new ByteArrayInputStream(new byte[]{1}));
        verify(mockPreparedStatement, times(4)).setAsciiStream(anyInt(), any(InputStream.class));
    }

    // --- setBinaryStream(InputStream) map happy paths ---

    @Test
    public void testSetBinaryStream_MapBranch_OneOccurrence() throws SQLException {
        NamedQuery q = createMapBranchQuery(ImmutableList.of("a", "b", "c", "d", "e"));
        q.setBinaryStream("d", new ByteArrayInputStream(new byte[]{2}));
        verify(mockPreparedStatement).setBinaryStream(eq(4), any(InputStream.class));
    }

    @Test
    public void testSetBinaryStream_MapBranch_TwoOccurrences() throws SQLException {
        NamedQuery q = createMapBranchQuery(ImmutableList.of("a", "b", "c", "p", "p"));
        q.setBinaryStream("p", new ByteArrayInputStream(new byte[]{2}));
        verify(mockPreparedStatement, times(2)).setBinaryStream(anyInt(), any(InputStream.class));
    }

    @Test
    public void testSetBinaryStream_MapBranch_ThreeOccurrences() throws SQLException {
        NamedQuery q = createMapBranchQuery(ImmutableList.of("a", "b", "c", "p", "p", "p"));
        q.setBinaryStream("p", new ByteArrayInputStream(new byte[]{2}));
        verify(mockPreparedStatement, times(3)).setBinaryStream(anyInt(), any(InputStream.class));
    }

    @Test
    public void testSetBinaryStream_MapBranch_FourOccurrences() throws SQLException {
        NamedQuery q = createMapBranchQuery(ImmutableList.of("a", "p", "p", "p", "p"));
        q.setBinaryStream("p", new ByteArrayInputStream(new byte[]{2}));
        verify(mockPreparedStatement, times(4)).setBinaryStream(anyInt(), any(InputStream.class));
    }

    // --- setCharacterStream(Reader) map happy paths ---

    @Test
    public void testSetCharacterStream_MapBranch_OneOccurrence() throws SQLException {
        NamedQuery q = createMapBranchQuery(ImmutableList.of("a", "b", "c", "d", "e"));
        q.setCharacterStream("e", new StringReader("hi"));
        verify(mockPreparedStatement).setCharacterStream(eq(5), any(Reader.class));
    }

    @Test
    public void testSetCharacterStream_MapBranch_TwoOccurrences() throws SQLException {
        NamedQuery q = createMapBranchQuery(ImmutableList.of("a", "b", "c", "p", "p"));
        q.setCharacterStream("p", new StringReader("two"));
        verify(mockPreparedStatement, times(2)).setCharacterStream(anyInt(), any(Reader.class));
    }

    @Test
    public void testSetCharacterStream_MapBranch_ThreeOccurrences() throws SQLException {
        NamedQuery q = createMapBranchQuery(ImmutableList.of("a", "b", "c", "p", "p", "p"));
        q.setCharacterStream("p", new StringReader("three"));
        verify(mockPreparedStatement, times(3)).setCharacterStream(anyInt(), any(Reader.class));
    }

    @Test
    public void testSetCharacterStream_MapBranch_FourOccurrences() throws SQLException {
        NamedQuery q = createMapBranchQuery(ImmutableList.of("a", "p", "p", "p", "p"));
        q.setCharacterStream("p", new StringReader("four"));
        verify(mockPreparedStatement, times(4)).setCharacterStream(anyInt(), any(Reader.class));
    }

    // --- setBlob(Blob) all paths ---

    @Test
    public void testSetBlob_LoopBranch_ParamNotFound() throws SQLException {
        assertThrows(IllegalArgumentException.class,
                () -> namedQuery.setBlob("unknown", mock(Blob.class)));
    }

    @Test
    public void testSetBlob_MapBranch_NotFound() throws SQLException {
        NamedQuery q = createMapBranchQuery(ImmutableList.of("a", "b", "c", "d", "e"));
        assertThrows(IllegalArgumentException.class, () -> q.setBlob("unknown", mock(Blob.class)));
    }

    @Test
    public void testSetBlob_MapBranch_OneOccurrence() throws SQLException {
        Blob blob = mock(Blob.class);
        NamedQuery q = createMapBranchQuery(ImmutableList.of("a", "b", "c", "d", "e"));
        q.setBlob("a", blob);
        verify(mockPreparedStatement).setBlob(1, blob);
    }

    @Test
    public void testSetBlob_MapBranch_TwoOccurrences() throws SQLException {
        Blob blob = mock(Blob.class);
        NamedQuery q = createMapBranchQuery(ImmutableList.of("a", "b", "c", "p", "p"));
        q.setBlob("p", blob);
        verify(mockPreparedStatement, times(2)).setBlob(anyInt(), eq(blob));
    }

    @Test
    public void testSetBlob_MapBranch_ThreeOccurrences() throws SQLException {
        Blob blob = mock(Blob.class);
        NamedQuery q = createMapBranchQuery(ImmutableList.of("a", "b", "c", "p", "p", "p"));
        q.setBlob("p", blob);
        verify(mockPreparedStatement, times(3)).setBlob(anyInt(), eq(blob));
    }

    @Test
    public void testSetBlob_MapBranch_FourOccurrences() throws SQLException {
        Blob blob = mock(Blob.class);
        NamedQuery q = createMapBranchQuery(ImmutableList.of("a", "p", "p", "p", "p"));
        q.setBlob("p", blob);
        verify(mockPreparedStatement, times(4)).setBlob(anyInt(), eq(blob));
    }

    // --- setBlob(InputStream) all paths ---

    @Test
    public void testSetBlobStream_LoopBranch_ParamNotFound() throws SQLException {
        assertThrows(IllegalArgumentException.class,
                () -> namedQuery.setBlob("unknown", new ByteArrayInputStream(new byte[]{1})));
    }

    @Test
    public void testSetBlobStream_MapBranch_NotFound() throws SQLException {
        NamedQuery q = createMapBranchQuery(ImmutableList.of("a", "b", "c", "d", "e"));
        assertThrows(IllegalArgumentException.class,
                () -> q.setBlob("unknown", new ByteArrayInputStream(new byte[]{1})));
    }

    @Test
    public void testSetBlobStream_MapBranch_OneOccurrence() throws SQLException {
        NamedQuery q = createMapBranchQuery(ImmutableList.of("a", "b", "c", "d", "e"));
        q.setBlob("b", new ByteArrayInputStream(new byte[]{1}));
        verify(mockPreparedStatement).setBlob(eq(2), any(InputStream.class));
    }

    @Test
    public void testSetBlobStream_MapBranch_TwoOccurrences() throws SQLException {
        NamedQuery q = createMapBranchQuery(ImmutableList.of("a", "b", "c", "p", "p"));
        q.setBlob("p", new ByteArrayInputStream(new byte[]{1}));
        verify(mockPreparedStatement, times(2)).setBlob(anyInt(), any(InputStream.class));
    }

    @Test
    public void testSetBlobStream_MapBranch_ThreeOccurrences() throws SQLException {
        NamedQuery q = createMapBranchQuery(ImmutableList.of("a", "b", "c", "p", "p", "p"));
        q.setBlob("p", new ByteArrayInputStream(new byte[]{1}));
        verify(mockPreparedStatement, times(3)).setBlob(anyInt(), any(InputStream.class));
    }

    @Test
    public void testSetBlobStream_MapBranch_FourOccurrences() throws SQLException {
        NamedQuery q = createMapBranchQuery(ImmutableList.of("a", "p", "p", "p", "p"));
        q.setBlob("p", new ByteArrayInputStream(new byte[]{1}));
        verify(mockPreparedStatement, times(4)).setBlob(anyInt(), any(InputStream.class));
    }

    // --- setBlob(InputStream, long) all paths ---

    @Test
    public void testSetBlobStreamLong_LoopBranch_ParamNotFound() throws SQLException {
        assertThrows(IllegalArgumentException.class,
                () -> namedQuery.setBlob("unknown", new ByteArrayInputStream(new byte[]{1}), 1L));
    }

    @Test
    public void testSetBlobStreamLong_MapBranch_NotFound() throws SQLException {
        NamedQuery q = createMapBranchQuery(ImmutableList.of("a", "b", "c", "d", "e"));
        assertThrows(IllegalArgumentException.class,
                () -> q.setBlob("unknown", new ByteArrayInputStream(new byte[]{1}), 1L));
    }

    @Test
    public void testSetBlobStreamLong_MapBranch_OneOccurrence() throws SQLException {
        NamedQuery q = createMapBranchQuery(ImmutableList.of("a", "b", "c", "d", "e"));
        q.setBlob("c", new ByteArrayInputStream(new byte[]{1}), 1L);
        verify(mockPreparedStatement).setBlob(eq(3), any(InputStream.class), eq(1L));
    }

    @Test
    public void testSetBlobStreamLong_MapBranch_TwoOccurrences() throws SQLException {
        NamedQuery q = createMapBranchQuery(ImmutableList.of("a", "b", "c", "p", "p"));
        q.setBlob("p", new ByteArrayInputStream(new byte[]{1}), 1L);
        verify(mockPreparedStatement, times(2)).setBlob(anyInt(), any(InputStream.class), eq(1L));
    }

    @Test
    public void testSetBlobStreamLong_MapBranch_ThreeOccurrences() throws SQLException {
        NamedQuery q = createMapBranchQuery(ImmutableList.of("a", "b", "c", "p", "p", "p"));
        q.setBlob("p", new ByteArrayInputStream(new byte[]{1}), 1L);
        verify(mockPreparedStatement, times(3)).setBlob(anyInt(), any(InputStream.class), eq(1L));
    }

    @Test
    public void testSetBlobStreamLong_MapBranch_FourOccurrences() throws SQLException {
        NamedQuery q = createMapBranchQuery(ImmutableList.of("a", "p", "p", "p", "p"));
        q.setBlob("p", new ByteArrayInputStream(new byte[]{1}), 1L);
        verify(mockPreparedStatement, times(4)).setBlob(anyInt(), any(InputStream.class), eq(1L));
    }

    // --- setClob(Clob) all paths ---

    @Test
    public void testSetClob_LoopBranch_ParamNotFound() throws SQLException {
        assertThrows(IllegalArgumentException.class,
                () -> namedQuery.setClob("unknown", mock(Clob.class)));
    }

    @Test
    public void testSetClob_MapBranch_NotFound() throws SQLException {
        NamedQuery q = createMapBranchQuery(ImmutableList.of("a", "b", "c", "d", "e"));
        assertThrows(IllegalArgumentException.class, () -> q.setClob("unknown", mock(Clob.class)));
    }

    @Test
    public void testSetClob_MapBranch_OneOccurrence() throws SQLException {
        Clob clob = mock(Clob.class);
        NamedQuery q = createMapBranchQuery(ImmutableList.of("a", "b", "c", "d", "e"));
        q.setClob("d", clob);
        verify(mockPreparedStatement).setClob(4, clob);
    }

    @Test
    public void testSetClob_MapBranch_TwoOccurrences() throws SQLException {
        Clob clob = mock(Clob.class);
        NamedQuery q = createMapBranchQuery(ImmutableList.of("a", "b", "c", "p", "p"));
        q.setClob("p", clob);
        verify(mockPreparedStatement, times(2)).setClob(anyInt(), eq(clob));
    }

    @Test
    public void testSetClob_MapBranch_ThreeOccurrences() throws SQLException {
        Clob clob = mock(Clob.class);
        NamedQuery q = createMapBranchQuery(ImmutableList.of("a", "b", "c", "p", "p", "p"));
        q.setClob("p", clob);
        verify(mockPreparedStatement, times(3)).setClob(anyInt(), eq(clob));
    }

    @Test
    public void testSetClob_MapBranch_FourOccurrences() throws SQLException {
        Clob clob = mock(Clob.class);
        NamedQuery q = createMapBranchQuery(ImmutableList.of("a", "p", "p", "p", "p"));
        q.setClob("p", clob);
        verify(mockPreparedStatement, times(4)).setClob(anyInt(), eq(clob));
    }

    // --- setClob(Reader) all paths ---

    @Test
    public void testSetClobReader_LoopBranch_ParamNotFound() throws SQLException {
        assertThrows(IllegalArgumentException.class,
                () -> namedQuery.setClob("unknown", new StringReader("x")));
    }

    @Test
    public void testSetClobReader_MapBranch_NotFound() throws SQLException {
        NamedQuery q = createMapBranchQuery(ImmutableList.of("a", "b", "c", "d", "e"));
        assertThrows(IllegalArgumentException.class, () -> q.setClob("unknown", new StringReader("x")));
    }

    @Test
    public void testSetClobReader_MapBranch_OneOccurrence() throws SQLException {
        NamedQuery q = createMapBranchQuery(ImmutableList.of("a", "b", "c", "d", "e"));
        q.setClob("e", new StringReader("text"));
        verify(mockPreparedStatement).setClob(eq(5), any(Reader.class));
    }

    @Test
    public void testSetClobReader_MapBranch_TwoOccurrences() throws SQLException {
        NamedQuery q = createMapBranchQuery(ImmutableList.of("a", "b", "c", "p", "p"));
        q.setClob("p", new StringReader("text"));
        verify(mockPreparedStatement, times(2)).setClob(anyInt(), any(Reader.class));
    }

    @Test
    public void testSetClobReader_MapBranch_ThreeOccurrences() throws SQLException {
        NamedQuery q = createMapBranchQuery(ImmutableList.of("a", "b", "c", "p", "p", "p"));
        q.setClob("p", new StringReader("text"));
        verify(mockPreparedStatement, times(3)).setClob(anyInt(), any(Reader.class));
    }

    @Test
    public void testSetClobReader_MapBranch_FourOccurrences() throws SQLException {
        NamedQuery q = createMapBranchQuery(ImmutableList.of("a", "p", "p", "p", "p"));
        q.setClob("p", new StringReader("text"));
        verify(mockPreparedStatement, times(4)).setClob(anyInt(), any(Reader.class));
    }

    // --- setClob(Reader, long) all paths ---

    @Test
    public void testSetClobReaderLong_LoopBranch_ParamNotFound() throws SQLException {
        assertThrows(IllegalArgumentException.class,
                () -> namedQuery.setClob("unknown", new StringReader("x"), 1L));
    }

    @Test
    public void testSetClobReaderLong_MapBranch_NotFound() throws SQLException {
        NamedQuery q = createMapBranchQuery(ImmutableList.of("a", "b", "c", "d", "e"));
        assertThrows(IllegalArgumentException.class,
                () -> q.setClob("unknown", new StringReader("x"), 1L));
    }

    @Test
    public void testSetClobReaderLong_MapBranch_OneOccurrence() throws SQLException {
        NamedQuery q = createMapBranchQuery(ImmutableList.of("a", "b", "c", "d", "e"));
        q.setClob("a", new StringReader("hi"), 2L);
        verify(mockPreparedStatement).setClob(eq(1), any(Reader.class), eq(2L));
    }

    @Test
    public void testSetClobReaderLong_MapBranch_TwoOccurrences() throws SQLException {
        NamedQuery q = createMapBranchQuery(ImmutableList.of("a", "b", "c", "p", "p"));
        q.setClob("p", new StringReader("hi"), 2L);
        verify(mockPreparedStatement, times(2)).setClob(anyInt(), any(Reader.class), eq(2L));
    }

    @Test
    public void testSetClobReaderLong_MapBranch_ThreeOccurrences() throws SQLException {
        NamedQuery q = createMapBranchQuery(ImmutableList.of("a", "b", "c", "p", "p", "p"));
        q.setClob("p", new StringReader("hi"), 2L);
        verify(mockPreparedStatement, times(3)).setClob(anyInt(), any(Reader.class), eq(2L));
    }

    @Test
    public void testSetClobReaderLong_MapBranch_FourOccurrences() throws SQLException {
        NamedQuery q = createMapBranchQuery(ImmutableList.of("a", "p", "p", "p", "p"));
        q.setClob("p", new StringReader("hi"), 2L);
        verify(mockPreparedStatement, times(4)).setClob(anyInt(), any(Reader.class), eq(2L));
    }

    // --- setNClob(NClob) all paths ---

    @Test
    public void testSetNClob_LoopBranch_ParamNotFound() throws SQLException {
        assertThrows(IllegalArgumentException.class,
                () -> namedQuery.setNClob("unknown", mock(NClob.class)));
    }

    @Test
    public void testSetNClob_MapBranch_NotFound() throws SQLException {
        NamedQuery q = createMapBranchQuery(ImmutableList.of("a", "b", "c", "d", "e"));
        assertThrows(IllegalArgumentException.class, () -> q.setNClob("unknown", mock(NClob.class)));
    }

    @Test
    public void testSetNClob_MapBranch_OneOccurrence() throws SQLException {
        NClob nclob = mock(NClob.class);
        NamedQuery q = createMapBranchQuery(ImmutableList.of("a", "b", "c", "d", "e"));
        q.setNClob("b", nclob);
        verify(mockPreparedStatement).setNClob(2, nclob);
    }

    @Test
    public void testSetNClob_MapBranch_TwoOccurrences() throws SQLException {
        NClob nclob = mock(NClob.class);
        NamedQuery q = createMapBranchQuery(ImmutableList.of("a", "b", "c", "p", "p"));
        q.setNClob("p", nclob);
        verify(mockPreparedStatement, times(2)).setNClob(anyInt(), eq(nclob));
    }

    @Test
    public void testSetNClob_MapBranch_ThreeOccurrences() throws SQLException {
        NClob nclob = mock(NClob.class);
        NamedQuery q = createMapBranchQuery(ImmutableList.of("a", "b", "c", "p", "p", "p"));
        q.setNClob("p", nclob);
        verify(mockPreparedStatement, times(3)).setNClob(anyInt(), eq(nclob));
    }

    @Test
    public void testSetNClob_MapBranch_FourOccurrences() throws SQLException {
        NClob nclob = mock(NClob.class);
        NamedQuery q = createMapBranchQuery(ImmutableList.of("a", "p", "p", "p", "p"));
        q.setNClob("p", nclob);
        verify(mockPreparedStatement, times(4)).setNClob(anyInt(), eq(nclob));
    }

    // --- setNClob(Reader) all paths ---

    @Test
    public void testSetNClobReader_LoopBranch_ParamNotFound() throws SQLException {
        assertThrows(IllegalArgumentException.class,
                () -> namedQuery.setNClob("unknown", new StringReader("x")));
    }

    @Test
    public void testSetNClobReader_MapBranch_NotFound() throws SQLException {
        NamedQuery q = createMapBranchQuery(ImmutableList.of("a", "b", "c", "d", "e"));
        assertThrows(IllegalArgumentException.class,
                () -> q.setNClob("unknown", new StringReader("x")));
    }

    @Test
    public void testSetNClobReader_MapBranch_OneOccurrence() throws SQLException {
        NamedQuery q = createMapBranchQuery(ImmutableList.of("a", "b", "c", "d", "e"));
        q.setNClob("c", new StringReader("uni"));
        verify(mockPreparedStatement).setNClob(eq(3), any(Reader.class));
    }

    @Test
    public void testSetNClobReader_MapBranch_TwoOccurrences() throws SQLException {
        NamedQuery q = createMapBranchQuery(ImmutableList.of("a", "b", "c", "p", "p"));
        q.setNClob("p", new StringReader("uni"));
        verify(mockPreparedStatement, times(2)).setNClob(anyInt(), any(Reader.class));
    }

    @Test
    public void testSetNClobReader_MapBranch_ThreeOccurrences() throws SQLException {
        NamedQuery q = createMapBranchQuery(ImmutableList.of("a", "b", "c", "p", "p", "p"));
        q.setNClob("p", new StringReader("uni"));
        verify(mockPreparedStatement, times(3)).setNClob(anyInt(), any(Reader.class));
    }

    @Test
    public void testSetNClobReader_MapBranch_FourOccurrences() throws SQLException {
        NamedQuery q = createMapBranchQuery(ImmutableList.of("a", "p", "p", "p", "p"));
        q.setNClob("p", new StringReader("uni"));
        verify(mockPreparedStatement, times(4)).setNClob(anyInt(), any(Reader.class));
    }

    // --- setNClob(Reader, long) all paths ---

    @Test
    public void testSetNClobReaderLong_LoopBranch_ParamNotFound() throws SQLException {
        assertThrows(IllegalArgumentException.class,
                () -> namedQuery.setNClob("unknown", new StringReader("x"), 1L));
    }

    @Test
    public void testSetNClobReaderLong_MapBranch_NotFound() throws SQLException {
        NamedQuery q = createMapBranchQuery(ImmutableList.of("a", "b", "c", "d", "e"));
        assertThrows(IllegalArgumentException.class,
                () -> q.setNClob("unknown", new StringReader("x"), 1L));
    }

    @Test
    public void testSetNClobReaderLong_MapBranch_OneOccurrence() throws SQLException {
        NamedQuery q = createMapBranchQuery(ImmutableList.of("a", "b", "c", "d", "e"));
        q.setNClob("d", new StringReader("x"), 1L);
        verify(mockPreparedStatement).setNClob(eq(4), any(Reader.class), eq(1L));
    }

    @Test
    public void testSetNClobReaderLong_MapBranch_TwoOccurrences() throws SQLException {
        NamedQuery q = createMapBranchQuery(ImmutableList.of("a", "b", "c", "p", "p"));
        q.setNClob("p", new StringReader("x"), 1L);
        verify(mockPreparedStatement, times(2)).setNClob(anyInt(), any(Reader.class), eq(1L));
    }

    @Test
    public void testSetNClobReaderLong_MapBranch_ThreeOccurrences() throws SQLException {
        NamedQuery q = createMapBranchQuery(ImmutableList.of("a", "b", "c", "p", "p", "p"));
        q.setNClob("p", new StringReader("x"), 1L);
        verify(mockPreparedStatement, times(3)).setNClob(anyInt(), any(Reader.class), eq(1L));
    }

    @Test
    public void testSetNClobReaderLong_MapBranch_FourOccurrences() throws SQLException {
        NamedQuery q = createMapBranchQuery(ImmutableList.of("a", "p", "p", "p", "p"));
        q.setNClob("p", new StringReader("x"), 1L);
        verify(mockPreparedStatement, times(4)).setNClob(anyInt(), any(Reader.class), eq(1L));
    }

    // --- setURL all paths ---

    @Test
    public void testSetURL_LoopBranch_ParamNotFound() throws Exception {
        assertThrows(IllegalArgumentException.class,
                () -> namedQuery.setURL("unknown", new URL("http://example.com")));
    }

    @Test
    public void testSetURL_MapBranch_NotFound() throws Exception {
        NamedQuery q = createMapBranchQuery(ImmutableList.of("a", "b", "c", "d", "e"));
        assertThrows(IllegalArgumentException.class,
                () -> q.setURL("unknown", new URL("http://example.com")));
    }

    @Test
    public void testSetURL_MapBranch_OneOccurrence() throws Exception {
        URL url = new URL("http://example.com");
        NamedQuery q = createMapBranchQuery(ImmutableList.of("a", "b", "c", "d", "e"));
        q.setURL("a", url);
        verify(mockPreparedStatement).setURL(1, url);
    }

    @Test
    public void testSetURL_MapBranch_TwoOccurrences() throws Exception {
        URL url = new URL("http://example.com");
        NamedQuery q = createMapBranchQuery(ImmutableList.of("a", "b", "c", "p", "p"));
        q.setURL("p", url);
        verify(mockPreparedStatement, times(2)).setURL(anyInt(), eq(url));
    }

    @Test
    public void testSetURL_MapBranch_ThreeOccurrences() throws Exception {
        URL url = new URL("http://example.com");
        NamedQuery q = createMapBranchQuery(ImmutableList.of("a", "b", "c", "p", "p", "p"));
        q.setURL("p", url);
        verify(mockPreparedStatement, times(3)).setURL(anyInt(), eq(url));
    }

    @Test
    public void testSetURL_MapBranch_FourOccurrences() throws Exception {
        URL url = new URL("http://example.com");
        NamedQuery q = createMapBranchQuery(ImmutableList.of("a", "p", "p", "p", "p"));
        q.setURL("p", url);
        verify(mockPreparedStatement, times(4)).setURL(anyInt(), eq(url));
    }

    // --- setSQLXML all paths ---

    @Test
    public void testSetSQLXML_LoopBranch_ParamNotFound() throws SQLException {
        assertThrows(IllegalArgumentException.class,
                () -> namedQuery.setSQLXML("unknown", mock(SQLXML.class)));
    }

    @Test
    public void testSetSQLXML_MapBranch_NotFound() throws SQLException {
        NamedQuery q = createMapBranchQuery(ImmutableList.of("a", "b", "c", "d", "e"));
        assertThrows(IllegalArgumentException.class,
                () -> q.setSQLXML("unknown", mock(SQLXML.class)));
    }

    @Test
    public void testSetSQLXML_MapBranch_OneOccurrence() throws SQLException {
        SQLXML xml = mock(SQLXML.class);
        NamedQuery q = createMapBranchQuery(ImmutableList.of("a", "b", "c", "d", "e"));
        q.setSQLXML("b", xml);
        verify(mockPreparedStatement).setSQLXML(2, xml);
    }

    @Test
    public void testSetSQLXML_MapBranch_TwoOccurrences() throws SQLException {
        SQLXML xml = mock(SQLXML.class);
        NamedQuery q = createMapBranchQuery(ImmutableList.of("a", "b", "c", "p", "p"));
        q.setSQLXML("p", xml);
        verify(mockPreparedStatement, times(2)).setSQLXML(anyInt(), eq(xml));
    }

    @Test
    public void testSetSQLXML_MapBranch_ThreeOccurrences() throws SQLException {
        SQLXML xml = mock(SQLXML.class);
        NamedQuery q = createMapBranchQuery(ImmutableList.of("a", "b", "c", "p", "p", "p"));
        q.setSQLXML("p", xml);
        verify(mockPreparedStatement, times(3)).setSQLXML(anyInt(), eq(xml));
    }

    @Test
    public void testSetSQLXML_MapBranch_FourOccurrences() throws SQLException {
        SQLXML xml = mock(SQLXML.class);
        NamedQuery q = createMapBranchQuery(ImmutableList.of("a", "p", "p", "p", "p"));
        q.setSQLXML("p", xml);
        verify(mockPreparedStatement, times(4)).setSQLXML(anyInt(), eq(xml));
    }

    // --- setRowId all paths ---

    @Test
    public void testSetRowId_LoopBranch_ParamNotFound() throws SQLException {
        assertThrows(IllegalArgumentException.class,
                () -> namedQuery.setRowId("unknown", mock(RowId.class)));
    }

    @Test
    public void testSetRowId_MapBranch_NotFound() throws SQLException {
        NamedQuery q = createMapBranchQuery(ImmutableList.of("a", "b", "c", "d", "e"));
        assertThrows(IllegalArgumentException.class,
                () -> q.setRowId("unknown", mock(RowId.class)));
    }

    @Test
    public void testSetRowId_MapBranch_OneOccurrence() throws SQLException {
        RowId rowId = mock(RowId.class);
        NamedQuery q = createMapBranchQuery(ImmutableList.of("a", "b", "c", "d", "e"));
        q.setRowId("c", rowId);
        verify(mockPreparedStatement).setRowId(3, rowId);
    }

    @Test
    public void testSetRowId_MapBranch_TwoOccurrences() throws SQLException {
        RowId rowId = mock(RowId.class);
        NamedQuery q = createMapBranchQuery(ImmutableList.of("a", "b", "c", "p", "p"));
        q.setRowId("p", rowId);
        verify(mockPreparedStatement, times(2)).setRowId(anyInt(), eq(rowId));
    }

    @Test
    public void testSetRowId_MapBranch_ThreeOccurrences() throws SQLException {
        RowId rowId = mock(RowId.class);
        NamedQuery q = createMapBranchQuery(ImmutableList.of("a", "b", "c", "p", "p", "p"));
        q.setRowId("p", rowId);
        verify(mockPreparedStatement, times(3)).setRowId(anyInt(), eq(rowId));
    }

    @Test
    public void testSetRowId_MapBranch_FourOccurrences() throws SQLException {
        RowId rowId = mock(RowId.class);
        NamedQuery q = createMapBranchQuery(ImmutableList.of("a", "p", "p", "p", "p"));
        q.setRowId("p", rowId);
        verify(mockPreparedStatement, times(4)).setRowId(anyInt(), eq(rowId));
    }

    // --- setRef all paths ---

    @Test
    public void testSetRef_LoopBranch_ParamNotFound() throws SQLException {
        assertThrows(IllegalArgumentException.class,
                () -> namedQuery.setRef("unknown", mock(Ref.class)));
    }

    @Test
    public void testSetRef_MapBranch_NotFound() throws SQLException {
        NamedQuery q = createMapBranchQuery(ImmutableList.of("a", "b", "c", "d", "e"));
        assertThrows(IllegalArgumentException.class,
                () -> q.setRef("unknown", mock(Ref.class)));
    }

    @Test
    public void testSetRef_MapBranch_OneOccurrence() throws SQLException {
        Ref ref = mock(Ref.class);
        NamedQuery q = createMapBranchQuery(ImmutableList.of("a", "b", "c", "d", "e"));
        q.setRef("d", ref);
        verify(mockPreparedStatement).setRef(4, ref);
    }

    @Test
    public void testSetRef_MapBranch_TwoOccurrences() throws SQLException {
        Ref ref = mock(Ref.class);
        NamedQuery q = createMapBranchQuery(ImmutableList.of("a", "b", "c", "p", "p"));
        q.setRef("p", ref);
        verify(mockPreparedStatement, times(2)).setRef(anyInt(), eq(ref));
    }

    @Test
    public void testSetRef_MapBranch_ThreeOccurrences() throws SQLException {
        Ref ref = mock(Ref.class);
        NamedQuery q = createMapBranchQuery(ImmutableList.of("a", "b", "c", "p", "p", "p"));
        q.setRef("p", ref);
        verify(mockPreparedStatement, times(3)).setRef(anyInt(), eq(ref));
    }

    @Test
    public void testSetRef_MapBranch_FourOccurrences() throws SQLException {
        Ref ref = mock(Ref.class);
        NamedQuery q = createMapBranchQuery(ImmutableList.of("a", "p", "p", "p", "p"));
        q.setRef("p", ref);
        verify(mockPreparedStatement, times(4)).setRef(anyInt(), eq(ref));
    }

    // --- setArray all paths ---

    @Test
    public void testSetArray_LoopBranch_ParamNotFound() throws SQLException {
        assertThrows(IllegalArgumentException.class,
                () -> namedQuery.setArray("unknown", mock(Array.class)));
    }

    @Test
    public void testSetArray_MapBranch_NotFound() throws SQLException {
        NamedQuery q = createMapBranchQuery(ImmutableList.of("a", "b", "c", "d", "e"));
        assertThrows(IllegalArgumentException.class,
                () -> q.setArray("unknown", mock(Array.class)));
    }

    @Test
    public void testSetArray_MapBranch_OneOccurrence() throws SQLException {
        Array arr = mock(Array.class);
        NamedQuery q = createMapBranchQuery(ImmutableList.of("a", "b", "c", "d", "e"));
        q.setArray("e", arr);
        verify(mockPreparedStatement).setArray(5, arr);
    }

    @Test
    public void testSetArray_MapBranch_TwoOccurrences() throws SQLException {
        Array arr = mock(Array.class);
        NamedQuery q = createMapBranchQuery(ImmutableList.of("a", "b", "c", "p", "p"));
        q.setArray("p", arr);
        verify(mockPreparedStatement, times(2)).setArray(anyInt(), eq(arr));
    }

    @Test
    public void testSetArray_MapBranch_ThreeOccurrences() throws SQLException {
        Array arr = mock(Array.class);
        NamedQuery q = createMapBranchQuery(ImmutableList.of("a", "b", "c", "p", "p", "p"));
        q.setArray("p", arr);
        verify(mockPreparedStatement, times(3)).setArray(anyInt(), eq(arr));
    }

    @Test
    public void testSetArray_MapBranch_FourOccurrences() throws SQLException {
        Array arr = mock(Array.class);
        NamedQuery q = createMapBranchQuery(ImmutableList.of("a", "p", "p", "p", "p"));
        q.setArray("p", arr);
        verify(mockPreparedStatement, times(4)).setArray(anyInt(), eq(arr));
    }

    // --- setObject(Object) all paths ---

    @Test
    public void testSetObject_LoopBranch_ParamNotFound() throws SQLException {
        assertThrows(IllegalArgumentException.class,
                () -> namedQuery.setObject("unknown", (Object) null));
    }

    @Test
    public void testSetObject_MapBranch_NotFound() throws SQLException {
        NamedQuery q = createMapBranchQuery(ImmutableList.of("a", "b", "c", "d", "e"));
        assertThrows(IllegalArgumentException.class, () -> q.setObject("unknown", (Object) null));
    }

    @Test
    public void testSetObject_MapBranch_OneOccurrence() throws SQLException {
        NamedQuery q = createMapBranchQuery(ImmutableList.of("a", "b", "c", "d", "e"));
        q.setObject("a", (Object) null); // null goes through stmt.setObject directly
        verify(mockPreparedStatement).setObject(1, (Object) null);
    }

    @Test
    public void testSetObject_MapBranch_TwoOccurrences() throws SQLException {
        NamedQuery q = createMapBranchQuery(ImmutableList.of("a", "b", "c", "p", "p"));
        q.setObject("p", (Object) null);
        verify(mockPreparedStatement, times(2)).setObject(anyInt(), isNull());
    }

    @Test
    public void testSetObject_MapBranch_ThreeOccurrences() throws SQLException {
        NamedQuery q = createMapBranchQuery(ImmutableList.of("a", "b", "c", "p", "p", "p"));
        q.setObject("p", (Object) null);
        verify(mockPreparedStatement, times(3)).setObject(anyInt(), isNull());
    }

    @Test
    public void testSetObject_MapBranch_FourOccurrences() throws SQLException {
        NamedQuery q = createMapBranchQuery(ImmutableList.of("a", "p", "p", "p", "p"));
        q.setObject("p", (Object) null);
        verify(mockPreparedStatement, times(4)).setObject(anyInt(), isNull());
    }

    // --- setObject(Object, int) all paths ---

    @Test
    public void testSetObjectWithSqlType_LoopBranch_ParamNotFound() throws SQLException {
        assertThrows(IllegalArgumentException.class,
                () -> namedQuery.setObject("unknown", "value", Types.VARCHAR));
    }

    @Test
    public void testSetObjectWithSqlType_MapBranch_NotFound() throws SQLException {
        NamedQuery q = createMapBranchQuery(ImmutableList.of("a", "b", "c", "d", "e"));
        assertThrows(IllegalArgumentException.class,
                () -> q.setObject("unknown", "value", Types.VARCHAR));
    }

    @Test
    public void testSetObjectWithSqlType_MapBranch_OneOccurrence() throws SQLException {
        NamedQuery q = createMapBranchQuery(ImmutableList.of("a", "b", "c", "d", "e"));
        q.setObject("b", "val", Types.VARCHAR);
        verify(mockPreparedStatement).setObject(2, "val", Types.VARCHAR);
    }

    @Test
    public void testSetObjectWithSqlType_MapBranch_TwoOccurrences() throws SQLException {
        NamedQuery q = createMapBranchQuery(ImmutableList.of("a", "b", "c", "p", "p"));
        q.setObject("p", "val", Types.VARCHAR);
        verify(mockPreparedStatement, times(2)).setObject(anyInt(), eq("val"), eq(Types.VARCHAR));
    }

    @Test
    public void testSetObjectWithSqlType_MapBranch_ThreeOccurrences() throws SQLException {
        NamedQuery q = createMapBranchQuery(ImmutableList.of("a", "b", "c", "p", "p", "p"));
        q.setObject("p", "val", Types.VARCHAR);
        verify(mockPreparedStatement, times(3)).setObject(anyInt(), eq("val"), eq(Types.VARCHAR));
    }

    @Test
    public void testSetObjectWithSqlType_MapBranch_FourOccurrences() throws SQLException {
        NamedQuery q = createMapBranchQuery(ImmutableList.of("a", "p", "p", "p", "p"));
        q.setObject("p", "val", Types.VARCHAR);
        verify(mockPreparedStatement, times(4)).setObject(anyInt(), eq("val"), eq(Types.VARCHAR));
    }

    // --- setObject(Object, int, int) all paths ---

    @Test
    public void testSetObjectWithSqlTypeScale_LoopBranch_ParamNotFound() throws SQLException {
        assertThrows(IllegalArgumentException.class,
                () -> namedQuery.setObject("unknown", "value", Types.DECIMAL, 2));
    }

    @Test
    public void testSetObjectWithSqlTypeScale_MapBranch_NotFound() throws SQLException {
        NamedQuery q = createMapBranchQuery(ImmutableList.of("a", "b", "c", "d", "e"));
        assertThrows(IllegalArgumentException.class,
                () -> q.setObject("unknown", "value", Types.DECIMAL, 2));
    }

    @Test
    public void testSetObjectWithSqlTypeScale_MapBranch_OneOccurrence() throws SQLException {
        NamedQuery q = createMapBranchQuery(ImmutableList.of("a", "b", "c", "d", "e"));
        q.setObject("c", "9.99", Types.DECIMAL, 2);
        verify(mockPreparedStatement).setObject(3, "9.99", Types.DECIMAL, 2);
    }

    @Test
    public void testSetObjectWithSqlTypeScale_MapBranch_TwoOccurrences() throws SQLException {
        NamedQuery q = createMapBranchQuery(ImmutableList.of("a", "b", "c", "p", "p"));
        q.setObject("p", "9.99", Types.DECIMAL, 2);
        verify(mockPreparedStatement, times(2)).setObject(anyInt(), eq("9.99"), eq(Types.DECIMAL), eq(2));
    }

    @Test
    public void testSetObjectWithSqlTypeScale_MapBranch_ThreeOccurrences() throws SQLException {
        NamedQuery q = createMapBranchQuery(ImmutableList.of("a", "b", "c", "p", "p", "p"));
        q.setObject("p", "9.99", Types.DECIMAL, 2);
        verify(mockPreparedStatement, times(3)).setObject(anyInt(), eq("9.99"), eq(Types.DECIMAL), eq(2));
    }

    @Test
    public void testSetObjectWithSqlTypeScale_MapBranch_FourOccurrences() throws SQLException {
        NamedQuery q = createMapBranchQuery(ImmutableList.of("a", "p", "p", "p", "p"));
        q.setObject("p", "9.99", Types.DECIMAL, 2);
        verify(mockPreparedStatement, times(4)).setObject(anyInt(), eq("9.99"), eq(Types.DECIMAL), eq(2));
    }

    // --- setObject(Object, SQLType) all paths ---

    @Test
    public void testSetObjectWithJDBCType_LoopBranch_ParamNotFound() throws SQLException {
        assertThrows(IllegalArgumentException.class,
                () -> namedQuery.setObject("unknown", "value", JDBCType.VARCHAR));
    }

    @Test
    public void testSetObjectWithJDBCType_MapBranch_NotFound() throws SQLException {
        NamedQuery q = createMapBranchQuery(ImmutableList.of("a", "b", "c", "d", "e"));
        assertThrows(IllegalArgumentException.class,
                () -> q.setObject("unknown", "value", JDBCType.VARCHAR));
    }

    @Test
    public void testSetObjectWithJDBCType_MapBranch_OneOccurrence() throws SQLException {
        NamedQuery q = createMapBranchQuery(ImmutableList.of("a", "b", "c", "d", "e"));
        q.setObject("d", "val", JDBCType.VARCHAR);
        verify(mockPreparedStatement).setObject(4, "val", JDBCType.VARCHAR);
    }

    @Test
    public void testSetObjectWithJDBCType_MapBranch_TwoOccurrences() throws SQLException {
        NamedQuery q = createMapBranchQuery(ImmutableList.of("a", "b", "c", "p", "p"));
        q.setObject("p", "val", JDBCType.VARCHAR);
        verify(mockPreparedStatement, times(2)).setObject(anyInt(), eq("val"), eq((SQLType) JDBCType.VARCHAR));
    }

    @Test
    public void testSetObjectWithJDBCType_MapBranch_ThreeOccurrences() throws SQLException {
        NamedQuery q = createMapBranchQuery(ImmutableList.of("a", "b", "c", "p", "p", "p"));
        q.setObject("p", "val", JDBCType.VARCHAR);
        verify(mockPreparedStatement, times(3)).setObject(anyInt(), eq("val"), eq((SQLType) JDBCType.VARCHAR));
    }

    @Test
    public void testSetObjectWithJDBCType_MapBranch_FourOccurrences() throws SQLException {
        NamedQuery q = createMapBranchQuery(ImmutableList.of("a", "p", "p", "p", "p"));
        q.setObject("p", "val", JDBCType.VARCHAR);
        verify(mockPreparedStatement, times(4)).setObject(anyInt(), eq("val"), eq((SQLType) JDBCType.VARCHAR));
    }

    // --- setObject(Object, SQLType, int) all paths ---

    @Test
    public void testSetObjectWithJDBCTypeScale_LoopBranch_ParamNotFound() throws SQLException {
        assertThrows(IllegalArgumentException.class,
                () -> namedQuery.setObject("unknown", "val", JDBCType.DECIMAL, 2));
    }

    @Test
    public void testSetObjectWithJDBCTypeScale_MapBranch_NotFound() throws SQLException {
        NamedQuery q = createMapBranchQuery(ImmutableList.of("a", "b", "c", "d", "e"));
        assertThrows(IllegalArgumentException.class,
                () -> q.setObject("unknown", "val", JDBCType.DECIMAL, 2));
    }

    @Test
    public void testSetObjectWithJDBCTypeScale_MapBranch_OneOccurrence() throws SQLException {
        NamedQuery q = createMapBranchQuery(ImmutableList.of("a", "b", "c", "d", "e"));
        q.setObject("e", "9.99", JDBCType.DECIMAL, 2);
        verify(mockPreparedStatement).setObject(5, "9.99", JDBCType.DECIMAL, 2);
    }

    @Test
    public void testSetObjectWithJDBCTypeScale_MapBranch_TwoOccurrences() throws SQLException {
        NamedQuery q = createMapBranchQuery(ImmutableList.of("a", "b", "c", "p", "p"));
        q.setObject("p", "9.99", JDBCType.DECIMAL, 2);
        verify(mockPreparedStatement, times(2)).setObject(anyInt(), eq("9.99"), eq((SQLType) JDBCType.DECIMAL), eq(2));
    }

    @Test
    public void testSetObjectWithJDBCTypeScale_MapBranch_ThreeOccurrences() throws SQLException {
        NamedQuery q = createMapBranchQuery(ImmutableList.of("a", "b", "c", "p", "p", "p"));
        q.setObject("p", "9.99", JDBCType.DECIMAL, 2);
        verify(mockPreparedStatement, times(3)).setObject(anyInt(), eq("9.99"), eq((SQLType) JDBCType.DECIMAL), eq(2));
    }

    @Test
    public void testSetObjectWithJDBCTypeScale_MapBranch_FourOccurrences() throws SQLException {
        NamedQuery q = createMapBranchQuery(ImmutableList.of("a", "p", "p", "p", "p"));
        q.setObject("p", "9.99", JDBCType.DECIMAL, 2);
        verify(mockPreparedStatement, times(4)).setObject(anyInt(), eq("9.99"), eq((SQLType) JDBCType.DECIMAL), eq(2));
    }

    // --- setParameters(Map) happy path ---

    @Test
    public void testSetParameters_Map_SetsMatchingParams() throws SQLException {
        // namedQuery has "param1" at index 1 and "param2" at index 2 (loop branch, paramCount=2)
        // null values route through stmt.setObject directly
        Map<String, Object> params = new HashMap<>();
        params.put("param1", null);
        params.put("param2", null);
        params.put("z", "ignored"); // not in query — should be ignored
        namedQuery.setParameters(params);
        verify(mockPreparedStatement, times(2)).setObject(anyInt(), isNull());
    }

    @Test
    public void testSetParameters_Map_NullThrows() throws SQLException {
        assertThrows(IllegalArgumentException.class,
                () -> namedQuery.setParameters((Map<String, ?>) null));
    }

    // Helper test entity class
    private static class TestEntity {
        private String param1;
        private int param2;

        public String getParam1() {
            return param1;
        }

        public void setParam1(String param1) {
            this.param1 = param1;
        }

        public int getParam2() {
            return param2;
        }

        public void setParam2(int param2) {
            this.param2 = param2;
        }
    }
}
