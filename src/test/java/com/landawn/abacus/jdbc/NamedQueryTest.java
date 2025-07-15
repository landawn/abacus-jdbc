package com.landawn.abacus.jdbc;

import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
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
import java.sql.Connection;
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
        when(mockParsedSql.getNamedParameters()).thenReturn(ImmutableList.of("param1", "param2"));
        when(mockParsedSql.getParameterCount()).thenReturn(2);
        when(mockParsedSql.sql()).thenReturn("SELECT * FROM table WHERE col1 = :param1 AND col2 = :param2");

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

        verify(mockPreparedStatement).setInt(1, (int) 'A');
        assertSame(namedQuery, result);
    }

    @Test
    public void testSetIntCharacter() throws SQLException {
        String paramName = "param1";
        Character value = Character.valueOf('B');

        NamedQuery result = namedQuery.setInt(paramName, value);

        verify(mockPreparedStatement).setInt(1, (int) 'B');
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
    public void testMultipleOccurrencesOfParameter() throws SQLException {
        // Create a query with duplicate parameter names
        when(mockParsedSql.getNamedParameters()).thenReturn(ImmutableList.of("id", "id", "name"));
        when(mockParsedSql.getParameterCount()).thenReturn(3);
        namedQuery = new NamedQuery(mockPreparedStatement, mockParsedSql);

        namedQuery.setInt("id", 123);

        // Both occurrences of "id" should be set
        verify(mockPreparedStatement).setInt(1, 123);
        verify(mockPreparedStatement).setInt(2, 123);
    }

    @Test
    public void testParameterNotFound() {
        assertThrows(IllegalArgumentException.class, () -> {
            namedQuery.setString("nonExistentParam", "value");
        });
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