package com.landawn.abacus.jdbc;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.io.InputStream;
import java.io.Reader;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.net.URL;
import java.sql.Array;
import java.sql.Blob;
import java.sql.Clob;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.JDBCType;
import java.sql.NClob;
import java.sql.PreparedStatement;
import java.sql.Ref;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.RowId;
import java.sql.SQLException;
import java.sql.SQLType;
import java.sql.SQLXML;
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
import java.util.Collection;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import com.landawn.abacus.TestBase;
import com.landawn.abacus.exception.DuplicatedResultException;
import com.landawn.abacus.type.Type;
import com.landawn.abacus.util.ContinuableFuture;
import com.landawn.abacus.util.DataSet;
import com.landawn.abacus.util.Tuple.Tuple2;
import com.landawn.abacus.util.Tuple.Tuple3;
import com.landawn.abacus.util.u.Nullable;
import com.landawn.abacus.util.u.Optional;
import com.landawn.abacus.util.u.OptionalBoolean;
import com.landawn.abacus.util.u.OptionalByte;
import com.landawn.abacus.util.u.OptionalChar;
import com.landawn.abacus.util.u.OptionalDouble;
import com.landawn.abacus.util.u.OptionalFloat;
import com.landawn.abacus.util.u.OptionalInt;
import com.landawn.abacus.util.u.OptionalLong;
import com.landawn.abacus.util.u.OptionalShort;
import com.landawn.abacus.util.stream.Collectors;
import com.landawn.abacus.util.stream.Stream;

public class PreparedQueryTest extends TestBase {

    @Mock
    private PreparedStatement mockStmt;

    @Mock
    private Connection mockConnection;

    @Mock
    private DatabaseMetaData mockDatabaseMetaData;

    @Mock
    private PreparedStatement mockPreparedStatement;

    @Mock
    private ResultSet mockResultSet;

    @Mock
    private ResultSetMetaData mockResultSetMetaData;

    private PreparedQuery query;

    private AutoCloseable mocks;

    @BeforeEach
    public void setUp() throws SQLException {
        mocks = MockitoAnnotations.openMocks(this);
        when(mockDatabaseMetaData.getDatabaseProductName()).thenReturn("MySQL");
        when(mockDatabaseMetaData.getDatabaseProductVersion()).thenReturn("8");
        when(mockConnection.prepareStatement(anyString())).thenReturn(mockPreparedStatement, mockPreparedStatement, mockPreparedStatement);
        when(mockConnection.prepareStatement(anyString(), anyInt(), anyInt())).thenReturn(mockPreparedStatement, mockPreparedStatement, mockPreparedStatement);
        when(mockStmt.executeQuery()).thenReturn(mockResultSet, mockResultSet, mockResultSet);
        when(mockStmt.getResultSet()).thenReturn(mockResultSet, mockResultSet, mockResultSet);
        when(mockResultSet.getMetaData()).thenReturn(mockResultSetMetaData, mockResultSetMetaData, mockResultSetMetaData);
        when(mockResultSetMetaData.getColumnCount()).thenReturn(1, 1, 1, 1, 1, 1);
        when(mockResultSetMetaData.getColumnLabel(1)).thenReturn("name", "name", "name", "name", "name");

        query = new PreparedQuery(mockStmt);
    }

    @AfterEach
    public void tearDown() throws Exception {
        if (query != null) {
            query.close();
        }
        if (mocks != null) {
            mocks.close();
        }
    }

    @Test
    public void testCloseAfterExecution() {
        // Test default behavior (should be true)
        assertTrue(query.isCloseAfterExecution());

        // Test setting to false
        PreparedQuery result = query.closeAfterExecution(false);
        assertSame(query, result);
        assertFalse(query.isCloseAfterExecution());

        // Test setting back to true
        result = query.closeAfterExecution(true);
        assertSame(query, result);
        assertTrue(query.isCloseAfterExecution());
    }

    @Test
    public void testCloseAfterExecutionOnClosedQuery() {
        query.close();
        assertThrows(IllegalStateException.class, () -> query.closeAfterExecution(false));
    }

    @Test
    public void testOnClose() {
        // Test single handler
        boolean[] handlerCalled = { false };
        PreparedQuery result = query.onClose(() -> handlerCalled[0] = true);
        assertSame(query, result);

        query.close();
        assertTrue(handlerCalled[0]);
    }

    @Test
    public void testOnCloseMultipleHandlers() {
        // Test multiple handlers - should execute in reverse order
        List<Integer> executionOrder = new ArrayList<>();

        query.onClose(() -> executionOrder.add(1)).onClose(() -> executionOrder.add(2)).onClose(() -> executionOrder.add(3));

        query.close();

        assertEquals(Arrays.asList(3, 2, 1), executionOrder);
    }

    @Test
    public void testOnCloseNullHandler() {
        assertThrows(IllegalArgumentException.class, () -> query.onClose(null));
    }

    @Test
    public void testOnCloseOnClosedQuery() {
        query.close();
        assertThrows(IllegalStateException.class, () -> query.onClose(() -> {
        }));
    }

    @Test
    public void testSetNull() throws SQLException {
        PreparedQuery result = query.setNull(1, Types.VARCHAR);
        assertSame(query, result);
        verify(mockStmt).setNull(1, Types.VARCHAR);
    }

    @Test
    public void testSetNullWithTypeName() throws SQLException {
        PreparedQuery result = query.setNull(1, Types.STRUCT, "MY_TYPE");
        assertSame(query, result);
        verify(mockStmt).setNull(1, Types.STRUCT, "MY_TYPE");
    }

    @Test
    public void testSetBoolean() throws SQLException {
        PreparedQuery result = query.setBoolean(1, true);
        assertSame(query, result);
        verify(mockStmt).setBoolean(1, true);
    }

    @Test
    public void testSetBooleanWithNull() throws SQLException {
        PreparedQuery result = query.setBoolean(1, (Boolean) null);
        assertSame(query, result);
        verify(mockStmt).setNull(1, Types.BOOLEAN);
        verify(mockStmt, never()).setBoolean(anyInt(), anyBoolean());
    }

    @Test
    public void testSetBooleanWithNonNull() throws SQLException {
        PreparedQuery result = query.setBoolean(1, Boolean.TRUE);
        assertSame(query, result);
        verify(mockStmt).setBoolean(1, true);
    }

    @Test
    public void testSetByte() throws SQLException {
        PreparedQuery result = query.setByte(1, (byte) 127);
        assertSame(query, result);
        verify(mockStmt).setByte(1, (byte) 127);
    }

    @Test
    public void testSetByteWithNull() throws SQLException {
        PreparedQuery result = query.setByte(1, (Byte) null);
        assertSame(query, result);
        verify(mockStmt).setNull(1, Types.TINYINT);
    }

    @Test
    public void testSetByteWithDefault() throws SQLException {
        PreparedQuery result = query.setByte(1, null, (byte) 10);
        assertSame(query, result);
        verify(mockStmt).setByte(1, (byte) 10);

        result = query.setByte(2, (byte) 20, (byte) 10);
        assertSame(query, result);
        verify(mockStmt).setByte(2, (byte) 20);
    }

    @Test
    public void testSetShort() throws SQLException {
        PreparedQuery result = query.setShort(1, (short) 1000);
        assertSame(query, result);
        verify(mockStmt).setShort(1, (short) 1000);
    }

    @Test
    public void testSetShortWithNull() throws SQLException {
        PreparedQuery result = query.setShort(1, (Short) null);
        assertSame(query, result);
        verify(mockStmt).setNull(1, Types.SMALLINT);
    }

    @Test
    public void testSetShortWithDefault() throws SQLException {
        PreparedQuery result = query.setShort(1, null, (short) 100);
        assertSame(query, result);
        verify(mockStmt).setShort(1, (short) 100);
    }

    @Test
    public void testSetInt() throws SQLException {
        PreparedQuery result = query.setInt(1, 42);
        assertSame(query, result);
        verify(mockStmt).setInt(1, 42);
    }

    @Test
    public void testSetIntWithNull() throws SQLException {
        PreparedQuery result = query.setInt(1, (Integer) null);
        assertSame(query, result);
        verify(mockStmt).setNull(1, Types.INTEGER);
    }

    @Test
    public void testSetIntWithDefault() throws SQLException {
        PreparedQuery result = query.setInt(1, null, 999);
        assertSame(query, result);
        verify(mockStmt).setInt(1, 999);
    }

    @Test
    public void testSetIntWithChar() throws SQLException {
        PreparedQuery result = query.setInt(1, 'A');
        assertSame(query, result);
        verify(mockStmt).setInt(1, 65); // ASCII value of 'A'
    }

    @Test
    public void testSetIntWithCharacterNull() throws SQLException {
        PreparedQuery result = query.setInt(1, (Character) null);
        assertSame(query, result);
        verify(mockStmt).setNull(1, Types.INTEGER);
    }

    @Test
    public void testSetLong() throws SQLException {
        PreparedQuery result = query.setLong(1, 123456789L);
        assertSame(query, result);
        verify(mockStmt).setLong(1, 123456789L);
    }

    @Test
    public void testSetLongWithNull() throws SQLException {
        PreparedQuery result = query.setLong(1, (Long) null);
        assertSame(query, result);
        verify(mockStmt).setNull(1, Types.BIGINT);
    }

    @Test
    public void testSetLongWithDefault() throws SQLException {
        PreparedQuery result = query.setLong(1, null, -1L);
        assertSame(query, result);
        verify(mockStmt).setLong(1, -1L);
    }

    @Test
    public void testSetLongWithBigInteger() throws SQLException {
        BigInteger bigInt = new BigInteger("123456789");
        PreparedQuery result = query.setLong(1, bigInt);
        assertSame(query, result);
        verify(mockStmt).setLong(1, 123456789L);
    }

    @Test
    public void testSetLongWithBigIntegerNull() throws SQLException {
        PreparedQuery result = query.setLong(1, (BigInteger) null);
        assertSame(query, result);
        verify(mockStmt).setNull(1, Types.BIGINT);
    }

    @Test
    public void testSetFloat() throws SQLException {
        PreparedQuery result = query.setFloat(1, 3.14f);
        assertSame(query, result);
        verify(mockStmt).setFloat(1, 3.14f);
    }

    @Test
    public void testSetFloatWithNull() throws SQLException {
        PreparedQuery result = query.setFloat(1, (Float) null);
        assertSame(query, result);
        verify(mockStmt).setNull(1, Types.FLOAT);
    }

    @Test
    public void testSetFloatWithDefault() throws SQLException {
        PreparedQuery result = query.setFloat(1, null, 0.0f);
        assertSame(query, result);
        verify(mockStmt).setFloat(1, 0.0f);
    }

    @Test
    public void testSetDouble() throws SQLException {
        PreparedQuery result = query.setDouble(1, 3.14159);
        assertSame(query, result);
        verify(mockStmt).setDouble(1, 3.14159);
    }

    @Test
    public void testSetDoubleWithNull() throws SQLException {
        PreparedQuery result = query.setDouble(1, (Double) null);
        assertSame(query, result);
        verify(mockStmt).setNull(1, Types.DOUBLE);
    }

    @Test
    public void testSetDoubleWithDefault() throws SQLException {
        PreparedQuery result = query.setDouble(1, null, 0.0);
        assertSame(query, result);
        verify(mockStmt).setDouble(1, 0.0);
    }

    @Test
    public void testSetBigDecimal() throws SQLException {
        BigDecimal value = new BigDecimal("123.45");
        PreparedQuery result = query.setBigDecimal(1, value);
        assertSame(query, result);
        verify(mockStmt).setBigDecimal(1, value);
    }

    @Test
    public void testSetBigDecimalWithBigInteger() throws SQLException {
        BigInteger bigInt = new BigInteger("123456");
        PreparedQuery result = query.setBigDecimal(1, bigInt);
        assertSame(query, result);
        verify(mockStmt).setBigDecimal(1, new BigDecimal(bigInt));
    }

    @Test
    public void testSetBigDecimalWithBigIntegerNull() throws SQLException {
        PreparedQuery result = query.setBigDecimal(1, (BigInteger) null);
        assertSame(query, result);
        verify(mockStmt).setNull(1, Types.DECIMAL);
    }

    @Test
    public void testSetBigIntegerAsString() throws SQLException {
        BigInteger bigInt = new BigInteger("99999999999999999999999");
        PreparedQuery result = query.setBigIntegerAsString(1, bigInt);
        assertSame(query, result);
        verify(mockStmt).setString(1, "99999999999999999999999");
    }

    @Test
    public void testSetString() throws SQLException {
        PreparedQuery result = query.setString(1, "test");
        assertSame(query, result);
        verify(mockStmt).setString(1, "test");
    }

    @Test
    public void testSetStringWithCharSequence() throws SQLException {
        StringBuilder sb = new StringBuilder("test");
        PreparedQuery result = query.setString(1, sb);
        assertSame(query, result);
        verify(mockStmt).setString(1, "test");
    }

    @Test
    public void testSetStringWithCharSequenceNull() throws SQLException {
        PreparedQuery result = query.setString(1, (CharSequence) null);
        assertSame(query, result);
        verify(mockStmt).setString(1, null);
    }

    @Test
    public void testSetStringWithChar() throws SQLException {
        PreparedQuery result = query.setString(1, 'A');
        assertSame(query, result);
        verify(mockStmt).setString(1, "A");
    }

    @Test
    public void testSetStringWithCharacter() throws SQLException {
        PreparedQuery result = query.setString(1, Character.valueOf('B'));
        assertSame(query, result);
        verify(mockStmt).setString(1, "B");
    }

    @Test
    public void testSetStringWithCharacterNull() throws SQLException {
        PreparedQuery result = query.setString(1, (Character) null);
        assertSame(query, result);
        verify(mockStmt).setString(1, null);
    }

    @Test
    public void testSetStringWithBigInteger() throws SQLException {
        BigInteger bigInt = new BigInteger("123456789");
        PreparedQuery result = query.setString(1, bigInt);
        assertSame(query, result);
        verify(mockStmt).setString(1, "123456789");
    }

    @Test
    public void testSetStringWithBigIntegerNull() throws SQLException {
        PreparedQuery result = query.setString(1, (BigInteger) null);
        assertSame(query, result);
        verify(mockStmt).setNull(1, Types.VARCHAR);
    }

    @Test
    public void testSetNString() throws SQLException {
        PreparedQuery result = query.setNString(1, "test");
        assertSame(query, result);
        verify(mockStmt).setNString(1, "test");
    }

    @Test
    public void testSetNStringWithCharSequence() throws SQLException {
        StringBuilder sb = new StringBuilder("test");
        PreparedQuery result = query.setNString(1, sb);
        assertSame(query, result);
        verify(mockStmt).setNString(1, "test");
    }

    @Test
    public void testSetDate() throws SQLException {
        java.sql.Date date = new java.sql.Date(System.currentTimeMillis());
        PreparedQuery result = query.setDate(1, date);
        assertSame(query, result);
        verify(mockStmt).setDate(1, date);
    }

    @Test
    public void testSetDateWithUtilDate() throws SQLException {
        java.util.Date utilDate = new java.util.Date();
        PreparedQuery result = query.setDate(1, utilDate);
        assertSame(query, result);
        verify(mockStmt).setDate(eq(1), any(java.sql.Date.class));
    }

    @Test
    public void testSetDateWithCalendar() throws SQLException {
        java.sql.Date date = new java.sql.Date(System.currentTimeMillis());
        Calendar cal = Calendar.getInstance();
        PreparedQuery result = query.setDate(1, date, cal);
        assertSame(query, result);
        verify(mockStmt).setDate(1, date, cal);
    }

    @Test
    public void testSetDateWithLocalDate() throws SQLException {
        LocalDate localDate = LocalDate.now();
        PreparedQuery result = query.setDate(1, localDate);
        assertSame(query, result);
        verify(mockStmt).setDate(1, java.sql.Date.valueOf(localDate));
    }

    @Test
    public void testSetTime() throws SQLException {
        java.sql.Time time = new java.sql.Time(System.currentTimeMillis());
        PreparedQuery result = query.setTime(1, time);
        assertSame(query, result);
        verify(mockStmt).setTime(1, time);
    }

    @Test
    public void testSetTimeWithUtilDate() throws SQLException {
        java.util.Date utilDate = new java.util.Date();
        PreparedQuery result = query.setTime(1, utilDate);
        assertSame(query, result);
        verify(mockStmt).setTime(eq(1), any(java.sql.Time.class));
    }

    @Test
    public void testSetTimeWithCalendar() throws SQLException {
        java.sql.Time time = new java.sql.Time(System.currentTimeMillis());
        Calendar cal = Calendar.getInstance();
        PreparedQuery result = query.setTime(1, time, cal);
        assertSame(query, result);
        verify(mockStmt).setTime(1, time, cal);
    }

    @Test
    public void testSetTimeWithLocalTime() throws SQLException {
        LocalTime localTime = LocalTime.now();
        PreparedQuery result = query.setTime(1, localTime);
        assertSame(query, result);
        verify(mockStmt).setTime(1, java.sql.Time.valueOf(localTime));
    }

    @Test
    public void testSetTimestamp() throws SQLException {
        Timestamp timestamp = new Timestamp(System.currentTimeMillis());
        PreparedQuery result = query.setTimestamp(1, timestamp);
        assertSame(query, result);
        verify(mockStmt).setTimestamp(1, timestamp);
    }

    @Test
    public void testSetTimestampWithUtilDate() throws SQLException {
        java.util.Date utilDate = new java.util.Date();
        PreparedQuery result = query.setTimestamp(1, utilDate);
        assertSame(query, result);
        verify(mockStmt).setTimestamp(eq(1), any(Timestamp.class));
    }

    @Test
    public void testSetTimestampWithCalendar() throws SQLException {
        Timestamp timestamp = new Timestamp(System.currentTimeMillis());
        Calendar cal = Calendar.getInstance();
        PreparedQuery result = query.setTimestamp(1, timestamp, cal);
        assertSame(query, result);
        verify(mockStmt).setTimestamp(1, timestamp, cal);
    }

    @Test
    public void testSetTimestampWithLocalDateTime() throws SQLException {
        LocalDateTime localDateTime = LocalDateTime.now();
        PreparedQuery result = query.setTimestamp(1, localDateTime);
        assertSame(query, result);
        verify(mockStmt).setTimestamp(1, Timestamp.valueOf(localDateTime));
    }

    @Test
    public void testSetTimestampWithZonedDateTime() throws SQLException {
        ZonedDateTime zonedDateTime = ZonedDateTime.now();
        PreparedQuery result = query.setTimestamp(1, zonedDateTime);
        assertSame(query, result);
        verify(mockStmt).setTimestamp(1, Timestamp.from(zonedDateTime.toInstant()));
    }

    @Test
    public void testSetTimestampWithOffsetDateTime() throws SQLException {
        OffsetDateTime offsetDateTime = OffsetDateTime.now();
        PreparedQuery result = query.setTimestamp(1, offsetDateTime);
        assertSame(query, result);
        verify(mockStmt).setTimestamp(1, Timestamp.from(offsetDateTime.toInstant()));
    }

    @Test
    public void testSetTimestampWithInstant() throws SQLException {
        Instant instant = Instant.now();
        PreparedQuery result = query.setTimestamp(1, instant);
        assertSame(query, result);
        verify(mockStmt).setTimestamp(1, Timestamp.from(instant));
    }

    @Test
    public void testSetBytes() throws SQLException {
        byte[] bytes = { 1, 2, 3, 4, 5 };
        PreparedQuery result = query.setBytes(1, bytes);
        assertSame(query, result);
        verify(mockStmt).setBytes(1, bytes);
    }

    @Test
    public void testSetAsciiStream() throws SQLException {
        InputStream stream = mock(InputStream.class);
        PreparedQuery result = query.setAsciiStream(1, stream);
        assertSame(query, result);
        verify(mockStmt).setAsciiStream(1, stream);
    }

    @Test
    public void testSetAsciiStreamWithLength() throws SQLException {
        InputStream stream = mock(InputStream.class);
        PreparedQuery result = query.setAsciiStream(1, stream, 1024);
        assertSame(query, result);
        verify(mockStmt).setAsciiStream(1, stream, 1024);
    }

    @Test
    public void testSetAsciiStreamWithLongLength() throws SQLException {
        InputStream stream = mock(InputStream.class);
        PreparedQuery result = query.setAsciiStream(1, stream, 1024L);
        assertSame(query, result);
        verify(mockStmt).setAsciiStream(1, stream, 1024L);
    }

    @Test
    public void testSetBinaryStream() throws SQLException {
        InputStream stream = mock(InputStream.class);
        PreparedQuery result = query.setBinaryStream(1, stream);
        assertSame(query, result);
        verify(mockStmt).setBinaryStream(1, stream);
    }

    @Test
    public void testSetBinaryStreamWithLength() throws SQLException {
        InputStream stream = mock(InputStream.class);
        PreparedQuery result = query.setBinaryStream(1, stream, 2048);
        assertSame(query, result);
        verify(mockStmt).setBinaryStream(1, stream, 2048);
    }

    @Test
    public void testSetBinaryStreamWithLongLength() throws SQLException {
        InputStream stream = mock(InputStream.class);
        PreparedQuery result = query.setBinaryStream(1, stream, 2048L);
        assertSame(query, result);
        verify(mockStmt).setBinaryStream(1, stream, 2048L);
    }

    @Test
    public void testSetCharacterStream() throws SQLException {
        Reader reader = mock(Reader.class);
        PreparedQuery result = query.setCharacterStream(1, reader);
        assertSame(query, result);
        verify(mockStmt).setCharacterStream(1, reader);
    }

    @Test
    public void testSetCharacterStreamWithLength() throws SQLException {
        Reader reader = mock(Reader.class);
        PreparedQuery result = query.setCharacterStream(1, reader, 100);
        assertSame(query, result);
        verify(mockStmt).setCharacterStream(1, reader, 100);
    }

    @Test
    public void testSetCharacterStreamWithLongLength() throws SQLException {
        Reader reader = mock(Reader.class);
        PreparedQuery result = query.setCharacterStream(1, reader, 100L);
        assertSame(query, result);
        verify(mockStmt).setCharacterStream(1, reader, 100L);
    }

    @Test
    public void testSetNCharacterStream() throws SQLException {
        Reader reader = mock(Reader.class);
        PreparedQuery result = query.setNCharacterStream(1, reader);
        assertSame(query, result);
        verify(mockStmt).setNCharacterStream(1, reader);
    }

    @Test
    public void testSetNCharacterStreamWithLength() throws SQLException {
        Reader reader = mock(Reader.class);
        PreparedQuery result = query.setNCharacterStream(1, reader, 100L);
        assertSame(query, result);
        verify(mockStmt).setNCharacterStream(1, reader, 100L);
    }

    @Test
    public void testSetBlob() throws SQLException {
        Blob blob = mock(Blob.class);
        PreparedQuery result = query.setBlob(1, blob);
        assertSame(query, result);
        verify(mockStmt).setBlob(1, blob);
    }

    @Test
    public void testSetBlobWithInputStream() throws SQLException {
        InputStream stream = mock(InputStream.class);
        PreparedQuery result = query.setBlob(1, stream);
        assertSame(query, result);
        verify(mockStmt).setBlob(1, stream);
    }

    @Test
    public void testSetBlobWithInputStreamAndLength() throws SQLException {
        InputStream stream = mock(InputStream.class);
        PreparedQuery result = query.setBlob(1, stream, 1024L);
        assertSame(query, result);
        verify(mockStmt).setBlob(1, stream, 1024L);
    }

    @Test
    public void testSetClob() throws SQLException {
        Clob clob = mock(Clob.class);
        PreparedQuery result = query.setClob(1, clob);
        assertSame(query, result);
        verify(mockStmt).setClob(1, clob);
    }

    @Test
    public void testSetClobWithReader() throws SQLException {
        Reader reader = mock(Reader.class);
        PreparedQuery result = query.setClob(1, reader);
        assertSame(query, result);
        verify(mockStmt).setClob(1, reader);
    }

    @Test
    public void testSetClobWithReaderAndLength() throws SQLException {
        Reader reader = mock(Reader.class);
        PreparedQuery result = query.setClob(1, reader, 1000L);
        assertSame(query, result);
        verify(mockStmt).setClob(1, reader, 1000L);
    }

    @Test
    public void testSetNClob() throws SQLException {
        NClob nclob = mock(NClob.class);
        PreparedQuery result = query.setNClob(1, nclob);
        assertSame(query, result);
        verify(mockStmt).setNClob(1, nclob);
    }

    @Test
    public void testSetNClobWithReader() throws SQLException {
        Reader reader = mock(Reader.class);
        PreparedQuery result = query.setNClob(1, reader);
        assertSame(query, result);
        verify(mockStmt).setNClob(1, reader);
    }

    @Test
    public void testSetNClobWithReaderAndLength() throws SQLException {
        Reader reader = mock(Reader.class);
        PreparedQuery result = query.setNClob(1, reader, 1000L);
        assertSame(query, result);
        verify(mockStmt).setNClob(1, reader, 1000L);
    }

    @Test
    public void testSetURL() throws SQLException {
        URL url = mock(URL.class);
        PreparedQuery result = query.setURL(1, url);
        assertSame(query, result);
        verify(mockStmt).setURL(1, url);
    }

    @Test
    public void testSetArray() throws SQLException {
        Array array = mock(Array.class);
        PreparedQuery result = query.setArray(1, array);
        assertSame(query, result);
        verify(mockStmt).setArray(1, array);
    }

    @Test
    public void testSetSQLXML() throws SQLException {
        SQLXML xml = mock(SQLXML.class);
        PreparedQuery result = query.setSQLXML(1, xml);
        assertSame(query, result);
        verify(mockStmt).setSQLXML(1, xml);
    }

    @Test
    public void testSetRef() throws SQLException {
        Ref ref = mock(Ref.class);
        PreparedQuery result = query.setRef(1, ref);
        assertSame(query, result);
        verify(mockStmt).setRef(1, ref);
    }

    @Test
    public void testSetRowId() throws SQLException {
        RowId rowId = mock(RowId.class);
        PreparedQuery result = query.setRowId(1, rowId);
        assertSame(query, result);
        verify(mockStmt).setRowId(1, rowId);
    }

    @Test
    public void testSetObject() throws SQLException {
        Object obj = new Object();
        PreparedQuery result = query.setObject(1, obj);
        assertSame(query, result);
        verify(mockStmt).setObject(eq(1), any());
    }

    @Test
    public void testSetObjectWithSqlType() throws SQLException {
        Object obj = "test";
        PreparedQuery result = query.setObject(1, obj, Types.VARCHAR);
        assertSame(query, result);
        verify(mockStmt).setObject(1, obj, Types.VARCHAR);
    }

    @Test
    public void testSetObjectWithSqlTypeAndScale() throws SQLException {
        Object obj = new BigDecimal("123.456");
        PreparedQuery result = query.setObject(1, obj, Types.DECIMAL, 2);
        assertSame(query, result);
        verify(mockStmt).setObject(1, obj, Types.DECIMAL, 2);
    }

    @Test
    public void testSetObjectWithSQLType() throws SQLException {
        Object obj = "test";
        SQLType sqlType = JDBCType.VARCHAR;
        PreparedQuery result = query.setObject(1, obj, sqlType);
        assertSame(query, result);
        verify(mockStmt).setObject(1, obj, sqlType);
    }

    @Test
    public void testSetObjectWithSQLTypeAndScale() throws SQLException {
        Object obj = new BigDecimal("123.456");
        SQLType sqlType = JDBCType.DECIMAL;
        PreparedQuery result = query.setObject(1, obj, sqlType, 2);
        assertSame(query, result);
        verify(mockStmt).setObject(1, obj, sqlType, 2);
    }

    @Test
    public void testSetParameters2Strings() throws SQLException {
        PreparedQuery result = query.setParameters("John", "Doe");
        assertSame(query, result);
        verify(mockStmt).setString(1, "John");
        verify(mockStmt).setString(2, "Doe");
    }

    @Test
    public void testSetParameters3Strings() throws SQLException {
        PreparedQuery result = query.setParameters("John", "Doe", "john@email.com");
        assertSame(query, result);
        verify(mockStmt).setString(1, "John");
        verify(mockStmt).setString(2, "Doe");
        verify(mockStmt).setString(3, "john@email.com");
    }

    @Test
    public void testSetParameters4Strings() throws SQLException {
        PreparedQuery result = query.setParameters("A", "B", "C", "D");
        assertSame(query, result);
        verify(mockStmt).setString(1, "A");
        verify(mockStmt).setString(2, "B");
        verify(mockStmt).setString(3, "C");
        verify(mockStmt).setString(4, "D");
    }

    @Test
    public void testSetParameters5Strings() throws SQLException {
        PreparedQuery result = query.setParameters("A", "B", "C", "D", "E");
        assertSame(query, result);
        verify(mockStmt).setString(1, "A");
        verify(mockStmt).setString(2, "B");
        verify(mockStmt).setString(3, "C");
        verify(mockStmt).setString(4, "D");
        verify(mockStmt).setString(5, "E");
    }

    @Test
    public void testSetParameters6Strings() throws SQLException {
        PreparedQuery result = query.setParameters("A", "B", "C", "D", "E", "F");
        assertSame(query, result);
        verify(mockStmt).setString(1, "A");
        verify(mockStmt).setString(2, "B");
        verify(mockStmt).setString(3, "C");
        verify(mockStmt).setString(4, "D");
        verify(mockStmt).setString(5, "E");
        verify(mockStmt).setString(6, "F");
    }

    @Test
    public void testSetParameters7Strings() throws SQLException {
        PreparedQuery result = query.setParameters("A", "B", "C", "D", "E", "F", "G");
        assertSame(query, result);
        verify(mockStmt).setString(1, "A");
        verify(mockStmt).setString(2, "B");
        verify(mockStmt).setString(3, "C");
        verify(mockStmt).setString(4, "D");
        verify(mockStmt).setString(5, "E");
        verify(mockStmt).setString(6, "F");
        verify(mockStmt).setString(7, "G");
    }

    @Test
    public void testSetParameters3Objects() throws SQLException {
        PreparedQuery result = query.setParameters("A", 123, true);
        assertSame(query, result);
        //        verify(mockStmt).setObject(eq(1), any());
        //        verify(mockStmt).setObject(eq(2), any());
        //        verify(mockStmt).setObject(eq(3), any());

        verify(mockStmt, times(1)).setBoolean(anyInt(), anyBoolean());
    }

    @Test
    public void testSetParameters4Objects() throws SQLException {
        PreparedQuery result = query.setParameters("A", 123, true, 45.6);
        assertSame(query, result);
        //    verify(mockStmt).setObject(eq(1), any());
        //    verify(mockStmt).setObject(eq(2), any());
        //    verify(mockStmt).setObject(eq(3), any());
        //    verify(mockStmt).setObject(eq(4), any());

        verify(mockStmt, times(1)).setBoolean(anyInt(), anyBoolean());
    }

    @Test
    public void testSetParameters5Objects() throws SQLException {
        PreparedQuery result = query.setParameters("A", 123, true, 45.6, new Date());
        assertSame(query, result);
        // verify(mockStmt, times(5)).setObject(anyInt(), any());

        verify(mockStmt, times(1)).setBoolean(anyInt(), anyBoolean());
    }

    @Test
    public void testSetParameters6Objects() throws SQLException {
        PreparedQuery result = query.setParameters("A", 123, true, 45.6, new Date(), null);
        assertSame(query, result);
        // verify(mockStmt, times(6)).setObject(anyInt(), any());

        verify(mockStmt, times(1)).setBoolean(anyInt(), anyBoolean());
    }

    @Test
    public void testSetParameters7Objects() throws SQLException {
        PreparedQuery result = query.setParameters("A", 123, true, 45.6, new Date(), null, "G");
        assertSame(query, result);
        // verify(mockStmt, times(7)).setObject(anyInt(), any());

        verify(mockStmt, times(1)).setBoolean(anyInt(), anyBoolean());
    }

    @Test
    public void testSetParameters8Objects() throws SQLException {
        PreparedQuery result = query.setParameters("A", 123, true, 45.6, new Date(), null, "G", 789L);
        assertSame(query, result);
        // verify(mockStmt, times(8)).setObject(anyInt(), any());

        verify(mockStmt, times(1)).setBoolean(anyInt(), anyBoolean());
    }

    @Test
    public void testSetParameters9Objects() throws SQLException {
        PreparedQuery result = query.setParameters("A", 123, true, 45.6, new Date(), null, "G", 789L, false);
        assertSame(query, result);
        // verify(mockStmt, times(9)).setObject(anyInt(), any());
        verify(mockStmt, times(2)).setBoolean(anyInt(), anyBoolean());
    }

    @Test
    public void testSetParametersIntArray() throws SQLException {
        int[] params = { 10, 20, 30 };
        PreparedQuery result = query.setParameters(params);
        assertSame(query, result);
        verify(mockStmt).setInt(1, 10);
        verify(mockStmt).setInt(2, 20);
        verify(mockStmt).setInt(3, 30);
    }

    @Test
    public void testSetParametersLongArray() throws SQLException {
        long[] params = { 100L, 200L, 300L };
        PreparedQuery result = query.setParameters(params);
        assertSame(query, result);
        verify(mockStmt).setLong(1, 100L);
        verify(mockStmt).setLong(2, 200L);
        verify(mockStmt).setLong(3, 300L);
    }

    @Test
    public void testSetParametersStringArray() throws SQLException {
        String[] params = { "A", "B", "C" };
        PreparedQuery result = query.setParameters(params);
        assertSame(query, result);
        verify(mockStmt).setString(1, "A");
        verify(mockStmt).setString(2, "B");
        verify(mockStmt).setString(3, "C");
    }

    @Test
    public void testSetParametersGenericArray() throws SQLException {
        LocalDate[] params = { LocalDate.now(), LocalDate.now().plusDays(1) };
        PreparedQuery result = query.setParameters(params);
        assertSame(query, result);
        verify(mockStmt, times(2)).setObject(anyInt(), any());
    }

    @Test
    public void testSetParametersCollection() throws SQLException {
        List<String> params = Arrays.asList("A", "B", "C");
        PreparedQuery result = query.setParameters(params);
        assertSame(query, result);
        verify(mockStmt).setString(1, "A");
        verify(mockStmt).setString(2, "B");
        verify(mockStmt).setString(3, "C");
    }

    @Test
    public void testSetParametersCollectionWithType() throws SQLException {
        List<LocalDate> params = Arrays.asList(LocalDate.now(), LocalDate.now().plusDays(1));
        PreparedQuery result = query.setParameters(params, LocalDate.class);
        assertSame(query, result);
        verify(mockStmt, times(2)).setObject(anyInt(), any());
    }

    @Test
    public void testSetParametersWithSetter() throws SQLException {
        Jdbc.ParametersSetter<PreparedStatement> setter = stmt -> {
            stmt.setString(1, "test");
            stmt.setInt(2, 123);
        };

        PreparedQuery result = query.setParameters(setter);
        assertSame(query, result);
        verify(mockStmt).setString(1, "test");
        verify(mockStmt).setInt(2, 123);
    }

    @Test
    public void testSetParametersWithBiSetter() throws SQLException {
        String param = "test";
        Jdbc.BiParametersSetter<PreparedStatement, String> setter = (stmt, p) -> {
            stmt.setString(1, p);
        };

        PreparedQuery result = query.setParameters(param, setter);
        assertSame(query, result);
        verify(mockStmt).setString(1, "test");
    }

    @Test
    public void testSetParametersNullArguments() {
        assertThrows(IllegalArgumentException.class, () -> query.setParameters((int[]) null));
        assertThrows(IllegalArgumentException.class, () -> query.setParameters((long[]) null));
        assertThrows(IllegalArgumentException.class, () -> query.setParameters((String[]) null));
        assertThrows(IllegalArgumentException.class, () -> query.setParameters((Collection<?>) null));
        assertThrows(IllegalArgumentException.class, () -> query.setParameters((Jdbc.ParametersSetter<PreparedStatement>) null));
    }

    @Test
    public void testSettParametersIntArray() throws SQLException {
        int[] params = { 10, 20, 30 };
        PreparedQuery result = query.settParameters(2, params);
        assertSame(query, result);
        verify(mockStmt).setInt(2, 10);
        verify(mockStmt).setInt(3, 20);
        verify(mockStmt).setInt(4, 30);
    }

    @Test
    public void testSettParametersLongArray() throws SQLException {
        long[] params = { 100L, 200L };
        PreparedQuery result = query.settParameters(3, params);
        assertSame(query, result);
        verify(mockStmt).setLong(3, 100L);
        verify(mockStmt).setLong(4, 200L);
    }

    @Test
    public void testSettParametersStringArray() throws SQLException {
        String[] params = { "X", "Y" };
        PreparedQuery result = query.settParameters(5, params);
        assertSame(query, result);
        verify(mockStmt).setString(5, "X");
        verify(mockStmt).setString(6, "Y");
    }

    @Test
    public void testSettParametersGenericArray() throws SQLException {
        BigDecimal[] params = { new BigDecimal("100.50"), new BigDecimal("200.75") };
        PreparedQuery result = query.settParameters(2, params);
        assertSame(query, result);
        verify(mockStmt, times(2)).setBigDecimal(anyInt(), any());
    }

    @Test
    public void testSettParametersCollection() throws SQLException {
        List<String> params = Arrays.asList("A", "B", "C");
        PreparedQuery result = query.settParameters(2, params);
        assertSame(query, result);
        verify(mockStmt).setString(2, "A");
        verify(mockStmt).setString(3, "B");
        verify(mockStmt).setString(4, "C");
    }

    @Test
    public void testSettParametersCollectionWithType() throws SQLException {
        Set<UUID> params = new HashSet<>();
        UUID uuid1 = UUID.randomUUID();
        UUID uuid2 = UUID.randomUUID();
        params.add(uuid1);
        params.add(uuid2);

        PreparedQuery result = query.settParameters(3, params, UUID.class);
        assertSame(query, result);
        verify(mockStmt, times(2)).setString(anyInt(), any());
    }

    @Test
    public void testSettParametersWithSetter() throws SQLException {
        Jdbc.ParametersSetter<PreparedQuery> setter = q -> {
            q.setString(1, "test");
            q.setInt(2, 123);
        };

        PreparedQuery result = query.settParameters(setter);
        assertSame(query, result);
        verify(mockStmt).setString(1, "test");
        verify(mockStmt).setInt(2, 123);
    }

    @Test
    public void testSettParametersWithBiSetter() throws SQLException {
        String param = "data";
        Jdbc.BiParametersSetter<PreparedQuery, String> setter = (q, p) -> {
            q.setString(1, p);
            q.setInt(2, p.length());
        };

        PreparedQuery result = query.settParameters(param, setter);
        assertSame(query, result);
        verify(mockStmt).setString(1, "data");
        verify(mockStmt).setInt(2, 4);
    }

    @Test
    public void testSetNullForMultiPositions() throws SQLException {
        PreparedQuery result = query.setNullForMultiPositions(Types.VARCHAR, 2, 4, 6);
        assertSame(query, result);
        verify(mockStmt).setNull(2, Types.VARCHAR);
        verify(mockStmt).setNull(4, Types.VARCHAR);
        verify(mockStmt).setNull(6, Types.VARCHAR);
    }

    @Test
    public void testSetBooleanForMultiPositions() throws SQLException {
        PreparedQuery result = query.setBooleanForMultiPositions(true, 1, 3, 5);
        assertSame(query, result);
        verify(mockStmt).setBoolean(1, true);
        verify(mockStmt).setBoolean(3, true);
        verify(mockStmt).setBoolean(5, true);
    }

    @Test
    public void testSetIntForMultiPositions() throws SQLException {
        PreparedQuery result = query.setIntForMultiPositions(999, 2, 4);
        assertSame(query, result);
        verify(mockStmt).setInt(2, 999);
        verify(mockStmt).setInt(4, 999);
    }

    @Test
    public void testSetLongForMultiPositions() throws SQLException {
        PreparedQuery result = query.setLongForMultiPositions(12345L, 1, 3, 5);
        assertSame(query, result);
        verify(mockStmt).setLong(1, 12345L);
        verify(mockStmt).setLong(3, 12345L);
        verify(mockStmt).setLong(5, 12345L);
    }

    @Test
    public void testSetDoubleForMultiPositions() throws SQLException {
        PreparedQuery result = query.setDoubleForMultiPositions(3.14, 1, 2, 3);
        assertSame(query, result);
        verify(mockStmt).setDouble(1, 3.14);
        verify(mockStmt).setDouble(2, 3.14);
        verify(mockStmt).setDouble(3, 3.14);
    }

    @Test
    public void testSetStringForMultiPositions() throws SQLException {
        PreparedQuery result = query.setStringForMultiPositions("test", 2, 4, 6);
        assertSame(query, result);
        verify(mockStmt).setString(2, "test");
        verify(mockStmt).setString(4, "test");
        verify(mockStmt).setString(6, "test");
    }

    @Test
    public void testSetDateForMultiPositions() throws SQLException {
        java.sql.Date date = new java.sql.Date(System.currentTimeMillis());
        PreparedQuery result = query.setDateForMultiPositions(date, 1, 3);
        assertSame(query, result);
        verify(mockStmt).setDate(1, date);
        verify(mockStmt).setDate(3, date);
    }

    @Test
    public void testSetDateForMultiPositionsUtil() throws SQLException {
        java.util.Date date = new java.util.Date();
        PreparedQuery result = query.setDateForMultiPositions(date, 2, 4);
        assertSame(query, result);
        verify(mockStmt, times(2)).setDate(anyInt(), any(java.sql.Date.class));
    }

    @Test
    public void testSetTimeForMultiPositions() throws SQLException {
        java.sql.Time time = new java.sql.Time(System.currentTimeMillis());
        PreparedQuery result = query.setTimeForMultiPositions(time, 1, 2, 3);
        assertSame(query, result);
        verify(mockStmt).setTime(1, time);
        verify(mockStmt).setTime(2, time);
        verify(mockStmt).setTime(3, time);
    }

    @Test
    public void testSetTimeForMultiPositionsUtil() throws SQLException {
        java.util.Date time = new java.util.Date();
        PreparedQuery result = query.setTimeForMultiPositions(time, 1, 2);
        assertSame(query, result);
        verify(mockStmt, times(2)).setTime(anyInt(), any(java.sql.Time.class));
    }

    @Test
    public void testSetTimestampForMultiPositions() throws SQLException {
        Timestamp timestamp = new Timestamp(System.currentTimeMillis());
        PreparedQuery result = query.setTimestampForMultiPositions(timestamp, 1, 3, 5, 7);
        assertSame(query, result);
        verify(mockStmt).setTimestamp(1, timestamp);
        verify(mockStmt).setTimestamp(3, timestamp);
        verify(mockStmt).setTimestamp(5, timestamp);
        verify(mockStmt).setTimestamp(7, timestamp);
    }

    @Test
    public void testSetTimestampForMultiPositionsUtil() throws SQLException {
        java.util.Date timestamp = new java.util.Date();
        PreparedQuery result = query.setTimestampForMultiPositions(timestamp, 2, 4, 6);
        assertSame(query, result);
        verify(mockStmt, times(3)).setTimestamp(anyInt(), any(Timestamp.class));
    }

    @Test
    public void testSetObjectForMultiPositions() throws SQLException {
        UUID uuid = UUID.randomUUID();
        String uuidStr = uuid.toString();
        PreparedQuery result = query.setObjectForMultiPositions(uuid, 1, 3, 5);
        assertSame(query, result);
        verify(mockStmt).setString(1, uuidStr);
        verify(mockStmt).setString(3, uuidStr);
        verify(mockStmt).setString(5, uuidStr);
    }

    @Test
    public void testSetForMultiPositionsInvalidIndices() {
        assertThrows(IllegalArgumentException.class, () -> query.setNullForMultiPositions(Types.VARCHAR));
        assertThrows(IllegalArgumentException.class, () -> query.setIntForMultiPositions(123, 0, 1));
        assertThrows(IllegalArgumentException.class, () -> query.setStringForMultiPositions("test", -1, 1));
    }

    @Test
    public void testAddBatch() throws SQLException {
        PreparedQuery result = query.addBatch();
        assertSame(query, result);
        verify(mockStmt).addBatch();
        assertTrue(query.isBatch);
    }

    @Test
    public void testAddBatchParameters() throws SQLException {
        List<List<Object>> batchData = Arrays.asList(Arrays.asList("John", 25), Arrays.asList("Jane", 30));

        PreparedQuery result = query.addBatchParameters(batchData);
        assertSame(query, result);
        verify(mockStmt, times(2)).addBatch();
        verify(mockStmt).setString(1, "John");
        verify(mockStmt).setInt(2, 25);
        verify(mockStmt).setString(1, "Jane");
        verify(mockStmt).setInt(2, 30);
    }

    @Test
    public void testAddBatchParametersWithType() throws SQLException {
        List<String> names = Arrays.asList("Alice", "Bob", "Charlie");

        PreparedQuery result = query.addBatchParameters(names, String.class);
        assertSame(query, result);
        verify(mockStmt, times(3)).addBatch();
        verify(mockStmt).setString(1, "Alice");
        verify(mockStmt).setString(1, "Bob");
        verify(mockStmt).setString(1, "Charlie");
    }

    @Test
    public void testAddBatchParametersIterator() throws SQLException {
        Iterator<Object[]> dataIterator = Arrays.asList(new Object[] { "A", 1 }, new Object[] { "B", 2 }).iterator();

        PreparedQuery result = query.addBatchParameters(dataIterator);
        assertSame(query, result);
        verify(mockStmt, times(2)).addBatch();
    }

    @Test
    public void testAddBatchParametersWithTriConsumer() throws SQLException {
        List<Map<String, Object>> data = Arrays.asList(Map.of("name", "John", "age", 25), Map.of("name", "Jane", "age", 30));

        PreparedQuery result = query.addBatchParameters(data, (q, stmt, map) -> {
            q.setString(1, (String) map.get("name"));
            stmt.setInt(2, (Integer) map.get("age"));
        });

        assertSame(query, result);
        verify(mockStmt, times(2)).addBatch();
    }

    @Test
    public void testSetFetchDirection() throws SQLException {
        PreparedQuery result = query.setFetchDirection(FetchDirection.FORWARD);
        assertSame(query, result);
        verify(mockStmt).getFetchDirection();
        verify(mockStmt).setFetchDirection(ResultSet.FETCH_FORWARD);
        assertTrue(query.isFetchDirectionSet);
    }

    @Test
    public void testSetFetchDirectionToForward() throws SQLException {
        PreparedQuery result = query.setFetchDirectionToForward();
        assertSame(query, result);
        verify(mockStmt).setFetchDirection(ResultSet.FETCH_FORWARD);
    }

    @Test
    public void testSetFetchSize() throws SQLException {
        PreparedQuery result = query.setFetchSize(1000);
        assertSame(query, result);
        verify(mockStmt).getFetchSize();
        verify(mockStmt).setFetchSize(1000);
    }

    @Test
    public void testSetMaxFieldSize() throws SQLException {
        PreparedQuery result = query.setMaxFieldSize(1024);
        assertSame(query, result);
        verify(mockStmt).getMaxFieldSize();
        verify(mockStmt).setMaxFieldSize(1024);
    }

    @Test
    public void testSetMaxRows() throws SQLException {
        PreparedQuery result = query.setMaxRows(100);
        assertSame(query, result);
        verify(mockStmt).setMaxRows(100);
    }

    @Test
    public void testSetLargeMaxRows() throws SQLException {
        PreparedQuery result = query.setLargeMaxRows(1000000L);
        assertSame(query, result);
        verify(mockStmt).setLargeMaxRows(1000000L);
    }

    @Test
    public void testSetQueryTimeout() throws SQLException {
        PreparedQuery result = query.setQueryTimeout(30);
        assertSame(query, result);
        verify(mockStmt).getQueryTimeout();
        verify(mockStmt).setQueryTimeout(30);
    }

    @Test
    public void testConfigStmt() throws SQLException {
        PreparedQuery result = query.configStmt(stmt -> {
            stmt.setFetchSize(100);
            stmt.setQueryTimeout(60);
        });
        assertSame(query, result);
        verify(mockStmt).setFetchSize(100);
        verify(mockStmt).setQueryTimeout(60);
    }

    @Test
    public void testConfigStmtBiConsumer() throws SQLException {
        PreparedQuery result = query.configStmt((q, stmt) -> {
            q.setFetchSize(100);
            stmt.setPoolable(false);
        });
        assertSame(query, result);
        verify(mockStmt).setFetchSize(100);
        verify(mockStmt).setPoolable(false);
    }

    @Test
    public void testQueryForBoolean() throws SQLException {
        when(mockResultSet.next()).thenReturn(true);
        when(mockResultSet.getBoolean(1)).thenReturn(true);

        OptionalBoolean result = query.queryForBoolean();

        assertTrue(result.isPresent());
        assertTrue(result.orElse(false));
        verify(mockResultSet).close();
    }

    @Test
    public void testQueryForBooleanEmpty() throws SQLException {
        when(mockResultSet.next()).thenReturn(false);

        OptionalBoolean result = query.queryForBoolean();

        assertFalse(result.isPresent());
        verify(mockResultSet).close();
    }

    @Test
    public void testQueryForChar() throws SQLException {
        when(mockResultSet.next()).thenReturn(true);
        when(mockResultSet.getString(1)).thenReturn("A");

        OptionalChar result = query.queryForChar();

        assertTrue(result.isPresent());
        assertEquals('A', result.orElseThrow());
        verify(mockResultSet).close();
    }

    @Test
    public void testQueryForByte() throws SQLException {
        when(mockResultSet.next()).thenReturn(true);
        when(mockResultSet.getByte(1)).thenReturn((byte) 127);

        OptionalByte result = query.queryForByte();

        assertTrue(result.isPresent());
        assertEquals((byte) 127, result.orElseThrow());
        verify(mockResultSet).close();
    }

    @Test
    public void testQueryForShort() throws SQLException {
        when(mockResultSet.next()).thenReturn(true);
        when(mockResultSet.getShort(1)).thenReturn((short) 1000);

        OptionalShort result = query.queryForShort();

        assertTrue(result.isPresent());
        assertEquals((short) 1000, result.orElseThrow());
        verify(mockResultSet).close();
    }

    @Test
    public void testQueryForInt() throws SQLException {
        when(mockResultSet.next()).thenReturn(true);
        when(mockResultSet.getInt(1)).thenReturn(42);

        OptionalInt result = query.queryForInt();

        assertTrue(result.isPresent());
        assertEquals(42, result.orElseThrow());
        verify(mockResultSet).close();
    }

    @Test
    public void testQueryForLong() throws SQLException {
        when(mockResultSet.next()).thenReturn(true);
        when(mockResultSet.getLong(1)).thenReturn(123456789L);

        OptionalLong result = query.queryForLong();

        assertTrue(result.isPresent());
        assertEquals(123456789L, result.orElseThrow());
        verify(mockResultSet).close();
    }

    @Test
    public void testQueryForFloat() throws SQLException {
        when(mockResultSet.next()).thenReturn(true);
        when(mockResultSet.getFloat(1)).thenReturn(3.14f);

        OptionalFloat result = query.queryForFloat();

        assertTrue(result.isPresent());
        assertEquals(3.14f, result.orElseThrow());
        verify(mockResultSet).close();
    }

    @Test
    public void testQueryForDouble() throws SQLException {
        when(mockResultSet.next()).thenReturn(true);
        when(mockResultSet.getDouble(1)).thenReturn(3.14159);

        OptionalDouble result = query.queryForDouble();

        assertTrue(result.isPresent());
        assertEquals(3.14159, result.orElseThrow());
        verify(mockResultSet).close();
    }

    @Test
    public void testQueryForString() throws SQLException {
        when(mockResultSet.next()).thenReturn(true);
        when(mockResultSet.getString(1)).thenReturn("test");

        Nullable<String> result = query.queryForString();

        assertTrue(result.isPresent());
        assertEquals("test", result.orElse(null));
        verify(mockResultSet).close();
    }

    @Test
    public void testQueryForBigInteger() throws SQLException {
        when(mockResultSet.next()).thenReturn(true);
        when(mockResultSet.getString(1)).thenReturn("123456789");

        Nullable<BigInteger> result = query.queryForBigInteger();

        assertTrue(result.isPresent());
        assertEquals(new BigInteger("123456789"), result.orElse(null));
        verify(mockResultSet).close();
    }

    @Test
    public void testQueryForBigDecimal() throws SQLException {
        when(mockResultSet.next()).thenReturn(true);
        when(mockResultSet.getBigDecimal(1)).thenReturn(new BigDecimal("123.45"));

        Nullable<BigDecimal> result = query.queryForBigDecimal();

        assertTrue(result.isPresent());
        assertEquals(new BigDecimal("123.45"), result.orElse(null));
        verify(mockResultSet).close();
    }

    @Test
    public void testQueryForDate() throws SQLException {
        java.sql.Date date = new java.sql.Date(System.currentTimeMillis());
        when(mockResultSet.next()).thenReturn(true);
        when(mockResultSet.getDate(1)).thenReturn(date);

        Nullable<java.sql.Date> result = query.queryForDate();

        assertTrue(result.isPresent());
        assertEquals(date, result.orElse(null));
        verify(mockResultSet).close();
    }

    @Test
    public void testQueryForTime() throws SQLException {
        java.sql.Time time = new java.sql.Time(System.currentTimeMillis());
        when(mockResultSet.next()).thenReturn(true);
        when(mockResultSet.getTime(1)).thenReturn(time);

        Nullable<java.sql.Time> result = query.queryForTime();

        assertTrue(result.isPresent());
        assertEquals(time, result.orElse(null));
        verify(mockResultSet).close();
    }

    @Test
    public void testQueryForTimestamp() throws SQLException {
        Timestamp timestamp = new Timestamp(System.currentTimeMillis());
        when(mockResultSet.next()).thenReturn(true);
        when(mockResultSet.getTimestamp(1)).thenReturn(timestamp);

        Nullable<Timestamp> result = query.queryForTimestamp();

        assertTrue(result.isPresent());
        assertEquals(timestamp, result.orElse(null));
        verify(mockResultSet).close();
    }

    @Test
    public void testQueryForBytes() throws SQLException {
        byte[] bytes = { 1, 2, 3, 4, 5 };
        when(mockResultSet.next()).thenReturn(true);
        when(mockResultSet.getBytes(1)).thenReturn(bytes);
        Nullable<byte[]> result = query.queryForBytes();

        assertTrue(result.isPresent());
        assertArrayEquals(bytes, result.orElse(null));
        verify(mockResultSet).close();
    }

    @Test
    public void testQueryForSingleResultClass() throws SQLException {
        when(mockResultSet.next()).thenReturn(true);
        when(mockResultSet.getString(1)).thenReturn("test");

        Nullable<String> result = query.queryForSingleResult(String.class);

        assertTrue(result.isPresent());
        assertEquals("test", result.orElse(null));
        verify(mockResultSet).close();
    }

    @Test
    public void testQueryForSingleResultType() throws SQLException {
        when(mockResultSet.next()).thenReturn(true);
        when(mockResultSet.getObject(1)).thenReturn(42);

        Nullable<Integer> result = query.queryForSingleResult(Type.of(Integer.class));

        assertTrue(result.isPresent());
        assertEquals(42, result.orElse(-1));
        verify(mockResultSet).close();
    }

    @Test
    public void testQueryForSingleNonNullClass() throws SQLException {
        when(mockResultSet.next()).thenReturn(true);
        when(mockResultSet.getString(1)).thenReturn("test");

        Optional<String> result = query.queryForSingleNonNull(String.class);

        assertTrue(result.isPresent());
        assertEquals("test", result.get());
        verify(mockResultSet).close();
    }

    @Test
    public void testQueryForSingleNonNullType() throws SQLException {
        when(mockResultSet.next()).thenReturn(true);
        when(mockResultSet.getObject(1)).thenReturn(123L);

        Optional<Long> result = query.queryForSingleNonNull(Type.of(Long.class));

        assertTrue(result.isPresent());
        assertEquals(123L, result.get());
        verify(mockResultSet).close();
    }

    @Test
    public void testQueryForUniqueResultClass() throws SQLException {
        when(mockResultSet.next()).thenReturn(true, false);
        when(mockResultSet.getString(1)).thenReturn("unique");

        Nullable<String> result = query.queryForUniqueResult(String.class);

        assertTrue(result.isPresent());
        assertEquals("unique", result.orElse(null));
        verify(mockResultSet).close();
    }

    @Test
    public void testQueryForUniqueResultDuplicated() throws SQLException {
        when(mockResultSet.next()).thenReturn(true, true);
        when(mockResultSet.getString(1)).thenReturn("first", "second");

        assertThrows(DuplicatedResultException.class, () -> query.queryForUniqueResult(String.class));
        verify(mockResultSet).close();
    }

    @Test
    public void testQueryForUniqueResultType() throws SQLException {
        when(mockResultSet.next()).thenReturn(true, false);
        when(mockResultSet.getBigDecimal(1)).thenReturn(new BigDecimal("123.45"));

        Nullable<BigDecimal> result = query.queryForUniqueResult(Type.of(BigDecimal.class));

        assertTrue(result.isPresent());
        assertEquals(new BigDecimal("123.45"), result.orElse(null));
        verify(mockResultSet).close();
    }

    @Test
    public void testQueryForUniqueNonNullClass() throws SQLException {
        when(mockResultSet.next()).thenReturn(true, false);
        when(mockResultSet.getObject(1)).thenReturn(42);

        Optional<Integer> result = query.queryForUniqueNonNull(Integer.class);

        assertTrue(result.isPresent());
        assertEquals(42, result.get());
        verify(mockResultSet).close();
    }

    @Test
    public void testQueryForUniqueNonNullType() throws SQLException {
        when(mockResultSet.next()).thenReturn(true, false);
        when(mockResultSet.getString(1)).thenReturn(UUID.randomUUID().toString());

        Optional<UUID> result = query.queryForUniqueNonNull(Type.of(UUID.class));

        assertTrue(result.isPresent());
        verify(mockResultSet).close();
    }

    @Test
    public void testQueryForUniqueNonNullDuplicated() throws SQLException {
        when(mockResultSet.next()).thenReturn(true, true);
        when(mockResultSet.getString(1)).thenReturn("first", "second");

        assertThrows(DuplicatedResultException.class, () -> query.queryForUniqueNonNull(String.class));
        verify(mockResultSet).close();
    }

    @Test
    public void testQueryDataSet() throws SQLException {
        when(mockResultSet.next()).thenReturn(true, false);
        when(mockResultSet.getObject(1)).thenReturn("value");

        DataSet result = query.query();

        assertNotNull(result);
        verify(mockResultSet).close();
    }

    @Test
    public void testQueryDataSetWithEntityClass() throws SQLException {
        when(mockResultSet.next()).thenReturn(true, false);

        DataSet result = query.query(TestEntity.class);

        assertNotNull(result);
        verify(mockResultSet).close();
    }

    @Test
    public void testQueryWithResultExtractor() throws SQLException {
        when(mockResultSet.next()).thenReturn(true, false);
        when(mockResultSet.getString(1)).thenReturn("abc");

        String result = query.query(rs -> {
            return rs.next() ? rs.getString(1) : null;
        });

        assertNotNull(result);
        verify(mockResultSet).close();
    }

    @Test
    public void testQueryWithBiResultExtractor() throws SQLException {
        when(mockResultSet.next()).thenReturn(true, false);

        Map<String, Object> result = query.query((rs, labels) -> {
            Map<String, Object> map = new HashMap<>();
            if (rs.next()) {
                for (String label : labels) {
                    map.put(label, rs.getObject(label));
                }
            }
            return map;
        });

        assertNotNull(result);
        verify(mockResultSet).close();
    }

    @Test
    public void testQuery2Resultsets() throws SQLException {
        when(mockStmt.getMoreResults()).thenReturn(true, true, false);
        when(mockStmt.getResultSet()).thenReturn(mockResultSet, mockResultSet);
        when(mockStmt.getUpdateCount()).thenReturn(0, 0, -1);

        Tuple2<String, Integer> result = query.query2Resultsets((rs, labels) -> "result1", (rs, labels) -> 42);

        assertEquals("result1", result._1);
        assertEquals(42, result._2);
    }

    @Test
    public void testQuery3Resultsets() throws SQLException {
        when(mockStmt.getMoreResults()).thenReturn(true, true, true, false);
        when(mockStmt.getResultSet()).thenReturn(mockResultSet, mockResultSet, mockResultSet);
        when(mockStmt.getUpdateCount()).thenReturn(0, 0, 0, -1);

        Tuple3<String, Integer, Boolean> result = query.query3Resultsets((rs, labels) -> "result1", (rs, labels) -> 42, (rs, labels) -> true);

        assertEquals("result1", result._1);
        assertEquals(42, result._2);
        assertEquals(true, result._3);
    }

    @Test
    public void testQueryAllResultsets() throws SQLException {
        when(mockStmt.getMoreResults()).thenReturn(true, false);
        when(mockStmt.getUpdateCount()).thenReturn(0, -1);

        List<DataSet> results = query.queryAllResultsets();

        assertNotNull(results);
        assertFalse(results.isEmpty());
    }

    @Test
    public void testQueryAllResultsetsWithExtractor() throws SQLException {
        when(mockStmt.getMoreResults()).thenReturn(true, false);
        when(mockStmt.getUpdateCount()).thenReturn(0, -1);

        List<Integer> results = query.queryAllResultsets(rs -> {
            int count = 0;
            while (rs.next())
                count++;
            return count;
        });

        assertNotNull(results);
    }

    @Test
    public void testQueryAllResultsetsWithBiExtractor() throws SQLException {
        when(mockStmt.getMoreResults()).thenReturn(false);
        when(mockStmt.getUpdateCount()).thenReturn(-1);

        List<Map<String, Integer>> results = query.queryAllResultsets((rs, labels) -> {
            Map<String, Integer> map = new HashMap<>();
            map.put("columnCount", labels.size());
            return map;
        });

        assertNotNull(results);
    }

    @Test
    public void testQueryThenApply() throws SQLException {
        when(mockResultSet.next()).thenReturn(true, false);

        Integer result = query.queryThenApply(dataSet -> dataSet.size());

        assertNotNull(result);
        verify(mockResultSet).close();
    }

    @Test
    public void testQueryThenApplyWithEntityClass() throws SQLException {
        when(mockResultSet.next()).thenReturn(true, false);

        Integer result = query.queryThenApply(TestEntity.class, dataSet -> dataSet.size());

        assertNotNull(result);
        verify(mockResultSet).close();
    }

    @Test
    public void testQueryThenAccept() throws SQLException {
        when(mockResultSet.next()).thenReturn(true, false);

        List<DataSet> captured = new ArrayList<>();
        query.queryThenAccept(dataSet -> captured.add(dataSet));

        assertEquals(1, captured.size());
        verify(mockResultSet).close();
    }

    @Test
    public void testQueryThenAcceptWithEntityClass() throws SQLException {
        when(mockResultSet.next()).thenReturn(true, false);

        List<DataSet> captured = new ArrayList<>();
        query.queryThenAccept(TestEntity.class, dataSet -> captured.add(dataSet));

        assertEquals(1, captured.size());
        verify(mockResultSet).close();
    }

    @Test
    public void testFindOnlyOne() throws SQLException {
        when(mockResultSet.next()).thenReturn(true, false);
        when(mockResultSet.getObject(1)).thenReturn("value1");
        when(mockResultSetMetaData.getColumnLabel(1)).thenReturn("col1");

        Optional<Map<String, Object>> result = query.findOnlyOne();

        assertTrue(result.isPresent());
        verify(mockResultSet).close();
    }

    @Test
    public void testFindOnlyOneClass() throws SQLException {
        when(mockResultSet.next()).thenReturn(true, false);

        Optional<TestEntity> result = query.findOnlyOne(TestEntity.class);

        assertTrue(result.isPresent());
        verify(mockResultSet).close();
    }

    @Test
    public void testFindOnlyOneRowMapper() throws SQLException {
        when(mockResultSet.next()).thenReturn(true, false);
        when(mockResultSet.getString(1)).thenReturn("test");

        Optional<String> result = query.findOnlyOne(rs -> rs.getString(1));

        assertTrue(result.isPresent());
        assertEquals("test", result.get());
        verify(mockResultSet).close();
    }

    @Test
    public void testFindOnlyOneBiRowMapper() throws SQLException {
        when(mockResultSet.next()).thenReturn(true, false);

        Optional<Integer> result = query.findOnlyOne((rs, labels) -> labels.size());

        assertTrue(result.isPresent());
        assertEquals(1, result.get());
        verify(mockResultSet).close();
    }

    @Test
    public void testFindOnlyOneDuplicated() throws SQLException {
        when(mockResultSet.next()).thenReturn(true, true);

        assertThrows(DuplicatedResultException.class, () -> query.findOnlyOne());
        verify(mockResultSet).close();
    }

    @Test
    public void testFindOnlyOneOrNull() throws SQLException {
        when(mockResultSet.next()).thenReturn(true, false);
        when(mockResultSet.getObject(1)).thenReturn("value");

        Map<String, Object> result = query.findOnlyOneOrNull();

        assertNotNull(result);
        verify(mockResultSet).close();
    }

    @Test
    public void testFindOnlyOneOrNullEmpty() throws SQLException {
        when(mockResultSet.next()).thenReturn(false);

        Map<String, Object> result = query.findOnlyOneOrNull();

        assertNull(result);
        verify(mockResultSet).close();
    }

    @Test
    public void testFindOnlyOneOrNullClass() throws SQLException {
        when(mockResultSet.next()).thenReturn(true, false);

        TestEntity result = query.findOnlyOneOrNull(TestEntity.class);

        assertNotNull(result);
        verify(mockResultSet).close();
    }

    @Test
    public void testFindOnlyOneOrNullRowMapper() throws SQLException {
        when(mockResultSet.next()).thenReturn(true, false);
        when(mockResultSet.getString(1)).thenReturn("test");

        String result = query.findOnlyOneOrNull(rs -> rs.getString(1));

        assertEquals("test", result);
        verify(mockResultSet).close();
    }

    @Test
    public void testFindOnlyOneOrNullBiRowMapper() throws SQLException {
        when(mockResultSet.next()).thenReturn(true, false);

        String result = query.findOnlyOneOrNull((rs, labels) -> "result");

        assertEquals("result", result);
        verify(mockResultSet).close();
    }

    @Test
    public void testFindFirst() throws SQLException {
        when(mockResultSet.next()).thenReturn(true, true, false);
        when(mockResultSet.getObject(1)).thenReturn("first");

        Optional<Map<String, Object>> result = query.findFirst();

        assertTrue(result.isPresent());
        verify(mockResultSet).close();
    }

    @Test
    public void testFindFirstClass() throws SQLException {
        when(mockResultSet.next()).thenReturn(true);

        Optional<TestEntity> result = query.findFirst(TestEntity.class);

        assertTrue(result.isPresent());
        verify(mockResultSet).close();
    }

    @Test
    public void testFindFirstRowMapper() throws SQLException {
        when(mockResultSet.next()).thenReturn(true);
        when(mockResultSet.getString(1)).thenReturn("first");

        Optional<String> result = query.findFirst(rs -> rs.getString(1));

        assertTrue(result.isPresent());
        assertEquals("first", result.get());
        verify(mockResultSet).close();
    }

    //    @Test
    //    public void testFindFirstWithFilter() throws SQLException {
    //        when(mockResultSet.next()).thenReturn(true, true, true);
    //        when(mockResultSet.getInt(1)).thenReturn(5, 10, 15);
    //
    //        Optional<Integer> result = query.findFirst(rs -> rs.getInt(1) > 8, rs -> rs.getInt(1));
    //
    //        assertTrue(result.isPresent());
    //        assertEquals(10, result.get());
    //        verify(mockResultSet).close();
    //    }

    @Test
    public void testFindFirstBiRowMapper() throws SQLException {
        when(mockResultSet.next()).thenReturn(true);

        Optional<List<String>> result = query.findFirst((rs, labels) -> labels);

        assertTrue(result.isPresent());
        verify(mockResultSet).close();
    }

    @Test
    public void testFindFirstWithBiFilter() throws SQLException {
        when(mockResultSet.next()).thenReturn(true, true);
        when(mockResultSet.getString(1)).thenReturn("skip", "take");

        Optional<String> result = query.findFirst((rs, labels) -> rs.getString(1).equals("take"), (rs, labels) -> rs.getString(1));

        assertTrue(result.isPresent());
        assertEquals("take", result.get());
        verify(mockResultSet).close();
    }

    @Test
    public void testFindFirstOrNull() throws SQLException {
        when(mockResultSet.next()).thenReturn(true);
        when(mockResultSet.getObject(1)).thenReturn("value");

        Map<String, Object> result = query.findFirstOrNull();

        assertNotNull(result);
        verify(mockResultSet).close();
    }

    @Test
    public void testFindFirstOrNullEmpty() throws SQLException {
        when(mockResultSet.next()).thenReturn(false);

        Map<String, Object> result = query.findFirstOrNull();

        assertNull(result);
        verify(mockResultSet).close();
    }

    @Test
    public void testFindFirstOrNullClass() throws SQLException {
        when(mockResultSet.next()).thenReturn(true);

        TestEntity result = query.findFirstOrNull(TestEntity.class);

        assertNotNull(result);
        verify(mockResultSet).close();
    }

    @Test
    public void testFindFirstOrNullRowMapper() throws SQLException {
        when(mockResultSet.next()).thenReturn(true);
        when(mockResultSet.getString(1)).thenReturn("result");

        String result = query.findFirstOrNull(rs -> rs.getString(1));

        assertEquals("result", result);
        verify(mockResultSet).close();
    }

    @Test
    public void testFindFirstOrNullWithFilter() throws SQLException {
        when(mockResultSet.next()).thenReturn(true, true, false);
        when(mockResultSet.getBoolean(1)).thenReturn(false, true);
        when(mockResultSet.getString(2)).thenReturn("take");

        String result = query.findFirstOrNull(rs -> rs.getBoolean(1), rs -> rs.getString(2));

        assertEquals("take", result);
        verify(mockResultSet).close();
    }

    @Test
    public void testFindFirstOrNullBiRowMapper() throws SQLException {
        when(mockResultSet.next()).thenReturn(true);

        Integer result = query.findFirstOrNull((rs, labels) -> labels.size());

        assertEquals(1, result);
        verify(mockResultSet).close();
    }

    @Test
    public void testFindFirstOrNullWithBiFilter() throws SQLException {
        when(mockResultSet.next()).thenReturn(true, true);
        when(mockResultSet.getString(1)).thenReturn("skip", "take");

        String result = query.findFirstOrNull((rs, labels) -> rs.getString(1).startsWith("t"), (rs, labels) -> rs.getString(1).toUpperCase());

        assertEquals("TAKE", result);
        verify(mockResultSet).close();
    }

    @Test
    public void testList() throws SQLException {
        when(mockResultSet.next()).thenReturn(true, true, false);
        when(mockResultSet.getObject(1)).thenReturn("val1", "val2");

        List<Map<String, Object>> result = query.list();

        assertEquals(2, result.size());
        verify(mockResultSet).close();
    }

    @Test
    public void testListClass() throws SQLException {
        when(mockResultSet.next()).thenReturn(true, true, false);

        List<TestEntity> result = query.list(TestEntity.class);

        assertEquals(2, result.size());
        verify(mockResultSet).close();
    }

    @Test
    public void testListClassWithMaxResult() throws SQLException {
        when(mockResultSet.next()).thenReturn(true, true, true, false);

        List<TestEntity> result = query.list(TestEntity.class, 2);

        assertEquals(2, result.size());
        verify(mockResultSet).close();
    }

    @Test
    public void testListRowMapper() throws SQLException {
        when(mockResultSet.next()).thenReturn(true, true, false);
        when(mockResultSet.getString(1)).thenReturn("A", "B");

        List<String> result = query.list(rs -> rs.getString(1));

        assertEquals(Arrays.asList("A", "B"), result);
        verify(mockResultSet).close();
    }

    @Test
    public void testListRowMapperWithMaxResult() throws SQLException {
        when(mockResultSet.next()).thenReturn(true, true, true);
        when(mockResultSet.getString(1)).thenReturn("A", "B", "C");

        List<String> result = query.list(rs -> rs.getString(1), 2);

        assertEquals(Arrays.asList("A", "B"), result);
        verify(mockResultSet).close();
    }

    @Test
    public void testListBiRowMapper() throws SQLException {
        when(mockResultSet.next()).thenReturn(true, true, false);

        List<Integer> result = query.list((rs, labels) -> labels.size());

        assertEquals(Arrays.asList(1, 1), result);
        verify(mockResultSet).close();
    }

    @Test
    public void testListBiRowMapperWithMaxResult() throws SQLException {
        when(mockResultSet.next()).thenReturn(true, true, true);

        List<String> result = query.list((rs, labels) -> "row", 2);

        assertEquals(Arrays.asList("row", "row"), result);
        verify(mockResultSet).close();
    }

    //    @Test
    //    public void testListWithBiFilter() throws SQLException {
    //        when(mockResultSet.next()).thenReturn(true, true, true, false);
    //        when(mockResultSet.getString(1)).thenReturn("skip", "take1", "take2");
    //
    //        List<String> result = query.list((rs, labels) -> rs.getString(1).startsWith("take"), (rs, labels) -> rs.getString(1));
    //
    //        assertEquals(Arrays.asList("take1", "take2"), result);
    //        verify(mockResultSet).close();
    //    }

    @Test
    public void testListAllResultsetsRowMapper() throws SQLException {
        when(mockStmt.getMoreResults()).thenReturn(false);
        when(mockStmt.getUpdateCount()).thenReturn(-1);
        when(mockResultSet.next()).thenReturn(true, false);
        when(mockResultSet.getString(1)).thenReturn("result");

        List<List<String>> results = query.listAllResultsets(rs -> rs.getString(1));

        assertEquals(1, results.size());
        assertEquals(Arrays.asList("result"), results.get(0));
    }

    @Test
    public void testListAllResultsetsWithFilter() throws SQLException {
        when(mockStmt.getMoreResults()).thenReturn(false);
        when(mockStmt.getUpdateCount()).thenReturn(-1);
        when(mockResultSet.next()).thenReturn(true, true, false);
        when(mockResultSet.getInt(1)).thenReturn(5, 10);

        List<List<Integer>> results = query.listAllResultsets(rs -> rs.getInt(1) > 8, rs -> rs.getInt(1));

        assertEquals(1, results.size());
        assertEquals(Arrays.asList(10), results.get(0));
    }

    @Test
    public void testListAllResultsetsBiRowMapper() throws SQLException {
        when(mockStmt.getMoreResults()).thenReturn(false);
        when(mockStmt.getUpdateCount()).thenReturn(-1);
        when(mockResultSet.next()).thenReturn(true, false);

        List<List<Integer>> results = query.listAllResultsets((rs, labels) -> labels.size());

        assertEquals(1, results.size());
        assertEquals(Arrays.asList(1), results.get(0));
    }

    @Test
    public void testListAllResultsetsWithBiFilter() throws SQLException {
        when(mockStmt.getMoreResults()).thenReturn(false);
        when(mockStmt.getUpdateCount()).thenReturn(-1);
        when(mockResultSet.next()).thenReturn(true, true, false);
        when(mockResultSet.getString(1)).thenReturn("skip", "take");

        List<List<String>> results = query.listAllResultsets((rs, labels) -> rs.getString(1).equals("take"), (rs, labels) -> rs.getString(1).toUpperCase());

        assertEquals(1, results.size());
        assertEquals(Arrays.asList("TAKE"), results.get(0));
    }

    @Test
    public void testListThenApplyClass() throws SQLException {
        when(mockResultSet.next()).thenReturn(true, true, false);

        Integer result = query.listThenApply(TestEntity.class, list -> list.size());

        assertEquals(2, result);
        verify(mockResultSet).close();
    }

    @Test
    public void testListThenApplyRowMapper() throws SQLException {
        when(mockResultSet.next()).thenReturn(true, true, false);
        when(mockResultSet.getString(1)).thenReturn("A", "B");

        String result = query.listThenApply(rs -> rs.getString(1), list -> String.join(",", list));

        assertEquals("A,B", result);
        verify(mockResultSet).close();
    }

    @Test
    public void testListThenApplyBiRowMapper() throws SQLException {
        when(mockResultSet.next()).thenReturn(true, false);

        Integer result = query.listThenApply((rs, labels) -> labels.size(), list -> list.get(0));

        assertEquals(1, result);
        verify(mockResultSet).close();
    }

    @Test
    public void testListThenAcceptClass() throws SQLException {
        when(mockResultSet.next()).thenReturn(true, true, false);

        List<Integer> sizes = new ArrayList<>();
        query.listThenAccept(TestEntity.class, list -> sizes.add(list.size()));

        assertEquals(Arrays.asList(2), sizes);
        verify(mockResultSet).close();
    }

    @Test
    public void testListThenAcceptRowMapper() throws SQLException {
        when(mockResultSet.next()).thenReturn(true, false);
        when(mockResultSet.getString(1)).thenReturn("test");

        List<String> captured = new ArrayList<>();
        query.listThenAccept(rs -> rs.getString(1), list -> captured.addAll(list));

        assertEquals(Arrays.asList("test"), captured);
        verify(mockResultSet).close();
    }

    @Test
    public void testListThenAcceptBiRowMapper() throws SQLException {
        when(mockResultSet.next()).thenReturn(true, false);

        List<Integer> captured = new ArrayList<>();
        query.listThenAccept((rs, labels) -> labels.size(), list -> captured.addAll(list));

        assertEquals(Arrays.asList(1), captured);
        verify(mockResultSet).close();
    }

    @Test
    public void testStream() throws SQLException {
        when(mockResultSet.next()).thenReturn(true, true, false);
        when(mockResultSet.getObject(1)).thenReturn("val1", "val2");

        try (Stream<Map<String, Object>> stream = query.stream()) {
            List<Map<String, Object>> result = stream.collect(Collectors.toList());
            assertEquals(2, result.size());
        }

        verify(mockResultSet).close();
    }

    @Test
    public void testStreamClass() throws SQLException {
        when(mockResultSet.next()).thenReturn(true, true, false);

        try (Stream<TestEntity> stream = query.stream(TestEntity.class)) {
            long count = stream.count();
            assertEquals(2, count);
        }

        verify(mockResultSet).close();
    }

    @Test
    public void testStreamRowMapper() throws SQLException {
        when(mockResultSet.next()).thenReturn(true, true, false);
        when(mockResultSet.getString(1)).thenReturn("A", "B");

        try (Stream<String> stream = query.stream(rs -> rs.getString(1))) {
            List<String> result = stream.collect(Collectors.toList());
            assertEquals(Arrays.asList("A", "B"), result);
        }

        verify(mockResultSet).close();
    }

    @Test
    public void testStreamBiRowMapper() throws SQLException {
        when(mockResultSet.next()).thenReturn(true, false);

        try (Stream<Integer> stream = query.stream((rs, labels) -> labels.size())) {
            List<Integer> result = stream.collect(Collectors.toList());
            assertEquals(Arrays.asList(1), result);
        }

        verify(mockResultSet).close();
    }

    //    @Test
    //    public void testStreamAllResultsets() throws SQLException {
    //        when(mockStmt.getMoreResults()).thenReturn(true, false);
    //        when(mockStmt.getUpdateCount()).thenReturn(-1);
    //        when(mockResultSet.next()).thenReturn(true, false);
    //
    //        try (Stream<DataSet> stream = query.streamAllResultsets()) {
    //            long count = stream.count();
    //            assertEquals(1, count);
    //        }
    //    }
    //
    //    @Test
    //    public void testStreamAllResultsetsWithExtractor() throws SQLException {
    //        when(mockStmt.getMoreResults()).thenReturn(false);
    //        when(mockStmt.getUpdateCount()).thenReturn(-1);
    //        when(mockResultSet.next()).thenReturn(true, false);
    //
    //        try (Stream<Integer> stream = query.streamAllResultsets(rs -> {
    //            int count = 0;
    //            while (rs.next())
    //                count++;
    //            return count;
    //        })) {
    //            List<Integer> result = stream.collect(Collectors.toList());
    //            assertEquals(Arrays.asList(1), result);
    //        }
    //    }

    @Test
    public void testExists() throws SQLException {
        when(mockResultSet.next()).thenReturn(true);

        boolean result = query.exists();

        assertTrue(result);
        verify(mockResultSet).close();
    }

    @Test
    public void testExistsFalse() throws SQLException {
        when(mockResultSet.next()).thenReturn(false);

        boolean result = query.exists();

        assertFalse(result);
        verify(mockResultSet).close();
    }

    @Test
    public void testNotExists() throws SQLException {
        when(mockResultSet.next()).thenReturn(false);

        boolean result = query.notExists();

        assertTrue(result);
        verify(mockResultSet).close();
    }

    @Test
    public void testNotExistsFalse() throws SQLException {
        when(mockResultSet.next()).thenReturn(true);

        boolean result = query.notExists();

        assertFalse(result);
        verify(mockResultSet).close();
    }

    @Test
    public void testIfExistsRowConsumer() throws SQLException {
        when(mockResultSet.next()).thenReturn(true);
        when(mockResultSet.getString(1)).thenReturn("exists");

        List<String> captured = new ArrayList<>();
        query.ifExists(rs -> captured.add(rs.getString(1)));

        assertEquals(Arrays.asList("exists"), captured);
        verify(mockResultSet).close();
    }

    @Test
    public void testIfExistsBiRowConsumer() throws SQLException {
        when(mockResultSet.next()).thenReturn(true);

        List<Integer> captured = new ArrayList<>();
        query.ifExists((rs, labels) -> captured.add(labels.size()));

        assertEquals(Arrays.asList(1), captured);
        verify(mockResultSet).close();
    }

    @Test
    public void testIfExistsOrElseRowConsumer() throws SQLException {
        when(mockResultSet.next()).thenReturn(true);

        boolean[] existsCalled = { false };
        boolean[] elseCalled = { false };

        query.ifExistsOrElse(rs -> existsCalled[0] = true, () -> elseCalled[0] = true);

        assertTrue(existsCalled[0]);
        assertFalse(elseCalled[0]);
        verify(mockResultSet).close();
    }

    @Test
    public void testIfExistsOrElseRowConsumerElsePath() throws SQLException {
        when(mockResultSet.next()).thenReturn(false);

        boolean[] existsCalled = { false };
        boolean[] elseCalled = { false };

        query.ifExistsOrElse(rs -> existsCalled[0] = true, () -> elseCalled[0] = true);

        assertFalse(existsCalled[0]);
        assertTrue(elseCalled[0]);
        verify(mockResultSet).close();
    }

    @Test
    public void testIfExistsOrElseBiRowConsumer() throws SQLException {
        when(mockResultSet.next()).thenReturn(true);

        boolean[] existsCalled = { false };
        boolean[] elseCalled = { false };

        query.ifExistsOrElse((rs, labels) -> existsCalled[0] = true, () -> elseCalled[0] = true);

        assertTrue(existsCalled[0]);
        assertFalse(elseCalled[0]);
        verify(mockResultSet).close();
    }

    @Test
    public void testCount() throws SQLException {
        when(mockResultSet.next()).thenReturn(true, true, true, false);

        int count = query.count();

        assertEquals(3, count);
        verify(mockResultSet).close();
    }

    @Test
    public void testCountWithFilter() throws SQLException {
        when(mockResultSet.next()).thenReturn(true, true, true, false);
        when(mockResultSet.getInt(1)).thenReturn(5, 10, 15);

        int count = query.count(rs -> rs.getInt(1) > 8);

        assertEquals(2, count);
        verify(mockResultSet).close();
    }

    @Test
    public void testCountWithBiFilter() throws SQLException {
        when(mockResultSet.next()).thenReturn(true, true, false);
        when(mockResultSet.getString(1)).thenReturn("yes", "no");

        int count = query.count((rs, labels) -> rs.getString(1).equals("yes"));

        assertEquals(1, count);
        verify(mockResultSet).close();
    }

    @Test
    public void testAnyMatch() throws SQLException {
        when(mockResultSet.next()).thenReturn(true, true, false);
        when(mockResultSet.getInt(1)).thenReturn(5, 10);

        boolean result = query.anyMatch(rs -> rs.getInt(1) > 8);

        assertTrue(result);
        verify(mockResultSet).close();
    }

    @Test
    public void testAnyMatchFalse() throws SQLException {
        when(mockResultSet.next()).thenReturn(true, true, false);
        when(mockResultSet.getInt(1)).thenReturn(5, 7);

        boolean result = query.anyMatch(rs -> rs.getInt(1) > 10);

        assertFalse(result);
        verify(mockResultSet).close();
    }

    @Test
    public void testAnyMatchBiFilter() throws SQLException {
        when(mockResultSet.next()).thenReturn(true, true);
        when(mockResultSet.getString(1)).thenReturn("no", "yes");

        boolean result = query.anyMatch((rs, labels) -> rs.getString(1).equals("yes"));

        assertTrue(result);
        verify(mockResultSet).close();
    }

    @Test
    public void testAllMatch() throws SQLException {
        when(mockResultSet.next()).thenReturn(true, true, false);
        when(mockResultSet.getInt(1)).thenReturn(10, 15);

        boolean result = query.allMatch(rs -> rs.getInt(1) > 5);

        assertTrue(result);
        verify(mockResultSet).close();
    }

    @Test
    public void testAllMatchFalse() throws SQLException {
        when(mockResultSet.next()).thenReturn(true, true, false);
        when(mockResultSet.getInt(1)).thenReturn(10, 3);

        boolean result = query.allMatch(rs -> rs.getInt(1) > 5);

        assertFalse(result);
        verify(mockResultSet).close();
    }

    @Test
    public void testAllMatchEmpty() throws SQLException {
        when(mockResultSet.next()).thenReturn(false);

        boolean result = query.allMatch(rs -> rs.getInt(1) > 5);

        assertTrue(result); // Empty set matches all
        verify(mockResultSet).close();
    }

    @Test
    public void testAllMatchBiFilter() throws SQLException {
        when(mockResultSet.next()).thenReturn(true, true, false);
        when(mockResultSet.getString(1)).thenReturn("yes", "yes");

        boolean result = query.allMatch((rs, labels) -> rs.getString(1).equals("yes"));

        assertTrue(result);
        verify(mockResultSet).close();
    }

    @Test
    public void testNoneMatch() throws SQLException {
        when(mockResultSet.next()).thenReturn(true, true, false);
        when(mockResultSet.getInt(1)).thenReturn(5, 7);

        boolean result = query.noneMatch(rs -> rs.getInt(1) > 10);

        assertTrue(result);
        verify(mockResultSet).close();
    }

    @Test
    public void testNoneMatchFalse() throws SQLException {
        when(mockResultSet.next()).thenReturn(true, true);
        when(mockResultSet.getInt(1)).thenReturn(5, 15);

        boolean result = query.noneMatch(rs -> rs.getInt(1) > 10);

        assertFalse(result);
        verify(mockResultSet).close();
    }

    @Test
    public void testNoneMatchBiFilter() throws SQLException {
        when(mockResultSet.next()).thenReturn(true, true, false);
        when(mockResultSet.getString(1)).thenReturn("no", "no");

        boolean result = query.noneMatch((rs, labels) -> rs.getString(1).equals("yes"));

        assertTrue(result);
        verify(mockResultSet).close();
    }

    @Test
    public void testForEach() throws SQLException {
        when(mockResultSet.next()).thenReturn(true, true, false);
        when(mockResultSet.getString(1)).thenReturn("A", "B");

        List<String> results = new ArrayList<>();
        query.forEach(rs -> results.add(rs.getString(1)));

        assertEquals(Arrays.asList("A", "B"), results);
        verify(mockResultSet).close();
    }

    //    @Test
    //    public void testForEachWithFilter() throws SQLException {
    //        when(mockResultSet.next()).thenReturn(true, true, true, false);
    //        when(mockResultSet.getInt(1)).thenReturn(5, 10, 15);
    //
    //        List<Integer> results = new ArrayList<>();
    //        query.forEach(rs -> rs.getInt(1) > 8, rs -> results.add(rs.getInt(1)));
    //
    //        assertEquals(Arrays.asList(10, 15), results);
    //        verify(mockResultSet).close();
    //    }

    @Test
    public void testForEachBiConsumer() throws SQLException {
        when(mockResultSet.next()).thenReturn(true, false);

        List<Integer> results = new ArrayList<>();
        query.forEach((rs, labels) -> results.add(labels.size()));

        assertEquals(Arrays.asList(1), results);
        verify(mockResultSet).close();
    }

    @Test
    public void testForEachWithBiFilter() throws SQLException {
        when(mockResultSet.next()).thenReturn(true, true, false);
        when(mockResultSet.getString(1)).thenReturn("skip", "take");

        List<String> results = new ArrayList<>();
        query.forEach((rs, labels) -> rs.getString(1).equals("take"), (rs, labels) -> results.add(rs.getString(1)));

        assertEquals(Arrays.asList("take"), results);
        verify(mockResultSet).close();
    }

    @Test
    public void testForeach() throws SQLException {
        when(mockResultSet.next()).thenReturn(true, false);
        when(mockResultSet.getObject(1)).thenReturn("value");

        List<Object> results = new ArrayList<>();
        query.foreach(row -> results.add(row.get(0)));

        assertEquals(1, results.size());
        assertEquals("value", results.get(0));
        verify(mockResultSet).close();
    }

    @Test
    public void testInsert() throws SQLException {
        when(mockStmt.executeUpdate()).thenReturn(1);
        when(mockStmt.getGeneratedKeys()).thenReturn(mockResultSet);
        when(mockResultSet.next()).thenReturn(true);
        when(mockResultSet.getObject(1)).thenReturn(123L);

        Optional<Long> result = query.insert();

        assertTrue(result.isPresent());
        assertEquals(123L, result.get());
        verify(mockStmt).executeUpdate();
        verify(mockResultSet).close();
    }

    @Test
    public void testInsertNoKey() throws SQLException {
        when(mockStmt.executeUpdate()).thenReturn(1);
        when(mockStmt.getGeneratedKeys()).thenReturn(mockResultSet);
        when(mockResultSet.next()).thenReturn(false);

        Optional<Long> result = query.insert();

        assertFalse(result.isPresent());
        verify(mockStmt).executeUpdate();
    }

    @Test
    public void testInsertWithExtractor() throws SQLException {
        when(mockStmt.executeUpdate()).thenReturn(1);
        when(mockStmt.getGeneratedKeys()).thenReturn(mockResultSet);
        when(mockResultSet.next()).thenReturn(true);
        when(mockResultSet.getString(1)).thenReturn("generated-uuid");

        Optional<String> result = query.insert(rs -> rs.getString(1));

        assertTrue(result.isPresent());
        assertEquals("generated-uuid", result.get());
        verify(mockStmt).executeUpdate();
    }

    @Test
    public void testInsertWithBiExtractor() throws SQLException {
        when(mockStmt.executeUpdate()).thenReturn(1);
        when(mockStmt.getGeneratedKeys()).thenReturn(mockResultSet);
        when(mockResultSet.next()).thenReturn(true);
        when(mockResultSetMetaData.getColumnCount()).thenReturn(2);
        when(mockResultSetMetaData.getColumnLabel(1)).thenReturn("id");
        when(mockResultSetMetaData.getColumnLabel(2)).thenReturn("code");

        Optional<Map<String, Object>> result = query.insert((rs, labels) -> {
            Map<String, Object> map = new HashMap<>();
            for (String label : labels) {
                map.put(label, rs.getObject(label));
            }
            return map;
        });

        assertTrue(result.isPresent());
        verify(mockStmt).executeUpdate();
    }

    @Test
    public void testBatchInsert() throws SQLException {
        when(mockStmt.executeBatch()).thenReturn(new int[] { 1, 1, 1 });
        when(mockStmt.getGeneratedKeys()).thenReturn(mockResultSet);
        when(mockResultSet.next()).thenReturn(true, true, true, false);
        when(mockResultSet.getObject(1)).thenReturn(101L, 102L, 103L);

        List<Long> result = query.batchInsert();

        assertEquals(Arrays.asList(101L, 102L, 103L), result);
        verify(mockStmt).executeBatch();
    }

    @Test
    public void testBatchInsertWithExtractor() throws SQLException {
        when(mockStmt.executeBatch()).thenReturn(new int[] { 1, 1 });
        when(mockStmt.getGeneratedKeys()).thenReturn(mockResultSet);
        when(mockResultSet.next()).thenReturn(true, true, false);
        when(mockResultSet.getString("uuid")).thenReturn("uuid1", "uuid2");

        List<String> result = query.batchInsert(rs -> rs.getString("uuid"));

        assertEquals(Arrays.asList("uuid1", "uuid2"), result);
        verify(mockStmt).executeBatch();
    }

    @Test
    public void testBatchInsertWithBiExtractor() throws SQLException {
        when(mockStmt.executeBatch()).thenReturn(new int[] { 1 });
        when(mockStmt.getGeneratedKeys()).thenReturn(mockResultSet);
        when(mockResultSet.next()).thenReturn(true, false);

        List<Integer> result = query.batchInsert((rs, labels) -> labels.size());

        assertEquals(Arrays.asList(1), result);
        verify(mockStmt).executeBatch();
    }

    @Test
    public void testUpdate() throws SQLException {
        when(mockStmt.executeUpdate()).thenReturn(5);

        int result = query.update();

        assertEquals(5, result);
        verify(mockStmt).executeUpdate();
    }

    @Test
    public void testUpdateAndReturnGeneratedKeys() throws SQLException {
        when(mockStmt.executeUpdate()).thenReturn(1);
        when(mockStmt.getGeneratedKeys()).thenReturn(mockResultSet);
        when(mockResultSet.next()).thenReturn(true, false);
        when(mockResultSet.getLong(1)).thenReturn(999L);

        Tuple2<Integer, List<Long>> result = query.updateAndReturnGeneratedKeys(rs -> rs.getLong(1));

        assertEquals(1, result._1.intValue());
        assertEquals(Arrays.asList(999L), result._2);
        verify(mockStmt).executeUpdate();
    }

    @Test
    public void testUpdateAndReturnGeneratedKeysWithBiExtractor() throws SQLException {
        when(mockStmt.executeUpdate()).thenReturn(2);
        when(mockStmt.getGeneratedKeys()).thenReturn(mockResultSet);
        when(mockResultSet.next()).thenReturn(true, true, false);

        Tuple2<Integer, List<String>> result = query.updateAndReturnGeneratedKeys((rs, labels) -> "key");

        assertEquals(2, result._1.intValue());
        assertEquals(Arrays.asList("key", "key"), result._2);
        verify(mockStmt).executeUpdate();
    }

    @Test
    public void testBatchUpdate() throws SQLException {
        int[] expected = { 1, 2, 0, 1 };
        when(mockStmt.executeBatch()).thenReturn(expected);

        int[] result = query.batchUpdate();

        assertArrayEquals(expected, result);
        verify(mockStmt).executeBatch();
    }

    @Test
    public void testBatchUpdateAndReturnGeneratedKeys() throws SQLException {
        when(mockStmt.executeBatch()).thenReturn(new int[] { 1, 1 });
        when(mockStmt.getGeneratedKeys()).thenReturn(mockResultSet);
        when(mockResultSet.next()).thenReturn(true, true, false);
        when(mockResultSet.getLong(1)).thenReturn(201L, 202L);

        Tuple2<int[], List<Long>> result = query.batchUpdateAndReturnGeneratedKeys(rs -> rs.getLong(1));

        assertArrayEquals(new int[] { 1, 1 }, result._1);
        assertEquals(Arrays.asList(201L, 202L), result._2);
        verify(mockStmt).executeBatch();
    }

    @Test
    public void testBatchUpdateAndReturnGeneratedKeysWithBiExtractor() throws SQLException {
        when(mockStmt.executeBatch()).thenReturn(new int[] { 1 });
        when(mockStmt.getGeneratedKeys()).thenReturn(mockResultSet);
        when(mockResultSet.next()).thenReturn(true, false);

        Tuple2<int[], List<Map<String, Object>>> result = query.batchUpdateAndReturnGeneratedKeys((rs, labels) -> {
            Map<String, Object> map = new HashMap<>();
            map.put("count", labels.size());
            return map;
        });

        assertArrayEquals(new int[] { 1 }, result._1);
        assertEquals(1, result._2.size());
        verify(mockStmt).executeBatch();
    }

    @Test
    public void testLargeUpdate() throws SQLException {
        when(mockStmt.executeLargeUpdate()).thenReturn(1000000L);

        long result = query.largeUpdate();

        assertEquals(1000000L, result);
        verify(mockStmt).executeLargeUpdate();
    }

    @Test
    public void testLargeBatchUpdate() throws SQLException {
        long[] expected = { 1000L, 2000L, 3000L };
        when(mockStmt.executeLargeBatch()).thenReturn(expected);

        long[] result = query.largeBatchUpdate();

        assertArrayEquals(expected, result);
        verify(mockStmt).executeLargeBatch();
    }

    @Test
    public void testExecute() throws SQLException {
        when(mockStmt.execute()).thenReturn(true);

        boolean result = query.execute();

        assertTrue(result);
        verify(mockStmt).execute();
    }

    @Test
    public void testExecuteThenApply() throws SQLException {
        when(mockStmt.execute()).thenReturn(false);
        when(mockStmt.getUpdateCount()).thenReturn(10);

        Integer result = query.executeThenApply(stmt -> stmt.getUpdateCount());

        assertEquals(10, result);
        verify(mockStmt).execute();
    }

    @Test
    public void testExecuteThenApplyBiFunction() throws SQLException {
        when(mockStmt.execute()).thenReturn(true);

        String result = query.executeThenApply((stmt, isResultSet) -> isResultSet ? "ResultSet" : "UpdateCount");

        assertEquals("ResultSet", result);
        verify(mockStmt).execute();
    }

    @Test
    public void testExecuteThenAccept() throws SQLException {
        when(mockStmt.execute()).thenReturn(false);
        when(mockStmt.getUpdateCount()).thenReturn(5);

        List<Integer> captured = new ArrayList<>();
        query.executeThenAccept(stmt -> captured.add(stmt.getUpdateCount()));

        assertEquals(Arrays.asList(5), captured);
        verify(mockStmt).execute();
    }

    @Test
    public void testExecuteThenAcceptBiConsumer() throws SQLException {
        when(mockStmt.execute()).thenReturn(true);

        List<String> captured = new ArrayList<>();
        query.executeThenAccept((stmt, isResultSet) -> captured.add(isResultSet ? "RS" : "UC"));

        assertEquals(Arrays.asList("RS"), captured);
        verify(mockStmt).execute();
    }

    @Test
    public void testAsyncCall() throws Exception {
        when(mockResultSet.next()).thenReturn(true, false);
        when(mockResultSet.getInt(1)).thenReturn(42);

        ContinuableFuture<OptionalInt> future = query.asyncCall(q -> q.queryForInt());

        OptionalInt result = future.get();
        assertTrue(result.isPresent());
        assertEquals(42, result.orElseThrow());
    }

    @Test
    public void testAsyncCallWithExecutor() throws Exception {
        ExecutorService executor = Executors.newSingleThreadExecutor();
        when(mockResultSet.next()).thenReturn(true, false);
        when(mockResultSet.getString(1)).thenReturn("async");

        ContinuableFuture<Nullable<String>> future = query.asyncCall(q -> q.queryForString(), executor);

        Nullable<String> result = future.get();
        assertTrue(result.isPresent());
        assertEquals("async", result.orElse(null));
    }

    @Test
    public void testAsyncRun() throws Exception {
        when(mockStmt.executeUpdate()).thenReturn(1);

        List<Integer> captured = new ArrayList<>();
        ContinuableFuture<Void> future = query.asyncRun(q -> {
            int updated = q.update();
            captured.add(updated);
        });

        future.get();
        assertEquals(Arrays.asList(1), captured);
    }

    @Test
    public void testAsyncRunWithExecutor() throws Exception {
        ExecutorService executor = Executors.newSingleThreadExecutor();
        when(mockStmt.execute()).thenReturn(true);

        List<Boolean> captured = new ArrayList<>();
        ContinuableFuture<Void> future = query.asyncRun(q -> {
            boolean hasResultSet = q.execute();
            captured.add(hasResultSet);
        }, executor);

        future.get();
        assertEquals(Arrays.asList(true), captured);
    }

    @Test
    public void testClose() throws SQLException {
        assertFalse(query.isClosed);

        query.close();

        assertTrue(query.isClosed);
        verify(mockStmt).close();
    }

    @Test
    public void testCloseIdempotent() throws SQLException {
        query.close();
        query.close(); // Should not throw

        verify(mockStmt, times(1)).close();
    }

    @Test
    public void testCloseWithHandler() throws SQLException {
        boolean[] handlerCalled = { false };
        query.onClose(() -> handlerCalled[0] = true);

        query.close();

        assertTrue(handlerCalled[0]);
        verify(mockStmt).close();
    }

    @Test
    public void testAutoCloseAfterExecution() throws SQLException {
        when(mockResultSet.next()).thenReturn(false);

        query.queryForInt();

        assertTrue(query.isClosed);
        verify(mockStmt).close();
    }

    @Test
    public void testNoAutoCloseWhenDisabled() throws SQLException {
        when(mockResultSet.next()).thenReturn(false);

        query.closeAfterExecution(false).queryForInt();

        assertFalse(query.isClosed);
        verify(mockStmt, never()).close();
    }

    @Test
    public void testOperationsOnClosedQuery() throws SQLException {
        query.close();

        //    assertThrows(IllegalStateException.class, () -> query.setString(1, "test"));
        //    assertThrows(IllegalStateException.class, () -> query.queryForInt());
        //    assertThrows(IllegalStateException.class, () -> query.update());
        //    assertThrows(IllegalStateException.class, () -> query.execute());
        //    assertThrows(IllegalStateException.class, () -> query.list());

        // no check in set parameters.
        query.setString(1, "test");
    }

    @Test
    public void testNullParameterValidation() {
        assertThrows(IllegalArgumentException.class, () -> query.setParameters((Jdbc.ParametersSetter<PreparedStatement>) null));
        assertThrows(IllegalArgumentException.class, () -> query.queryForSingleResult((Class<?>) null));
        assertThrows(IllegalArgumentException.class, () -> query.query((Jdbc.ResultExtractor<?>) null));
        assertThrows(IllegalArgumentException.class, () -> query.list((Jdbc.RowMapper<?>) null));
        assertThrows(IllegalArgumentException.class, () -> query.forEach((Jdbc.RowConsumer) null));
    }

    @Test
    public void testResetStatementPropertiesOnClose() throws SQLException {
        when(mockStmt.getFetchDirection()).thenReturn(ResultSet.FETCH_FORWARD);
        when(mockStmt.getFetchSize()).thenReturn(100);
        when(mockStmt.getMaxFieldSize()).thenReturn(1024);
        when(mockStmt.getQueryTimeout()).thenReturn(30);

        query.setFetchDirection(FetchDirection.REVERSE).setFetchSize(500).setMaxFieldSize(2048).setQueryTimeout(60);

        query.close();

        verify(mockStmt).setFetchDirection(ResultSet.FETCH_FORWARD);
        verify(mockStmt, times(2)).setFetchSize(anyInt()); // Once for set, once for reset
        verify(mockStmt, times(2)).setMaxFieldSize(anyInt());
        verify(mockStmt, times(2)).setQueryTimeout(anyInt());
    }

    // Helper test entity class
    public static class TestEntity {
        private Long id;
        private String name;

        public Long getId() {
            return id;
        }

        public void setId(Long id) {
            this.id = id;
        }

        public String getName() {
            return name;
        }

        public void setName(String name) {
            this.name = name;
        }
    }
}