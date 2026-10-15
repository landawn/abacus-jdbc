package com.landawn.abacus.jdbc;

import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import java.io.InputStream;
import java.io.Reader;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.net.URL;
import java.sql.Array;
import java.sql.Blob;
import java.sql.Clob;
import java.sql.JDBCType;
import java.sql.NClob;
import java.sql.PreparedStatement;
import java.sql.Ref;
import java.sql.ResultSet;
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
import java.util.Arrays;
import java.util.Calendar;
import java.util.Collection;
import java.util.Date;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import org.junit.jupiter.api.Test;

class PreparedQueryParameterBindingTest extends PreparedQueryTestSupport {

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
    public void testSetArray() throws SQLException {
        Array array = mock(Array.class);
        PreparedQuery result = query.setArray(1, array);
        assertSame(query, result);
        verify(mockStmt).setArray(1, array);
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
    public void testSetBoolean() throws SQLException {
        PreparedQuery result = query.setBoolean(1, true);
        assertSame(query, result);
        verify(mockStmt).setBoolean(1, true);
    }

    @Test
    public void testSetBooleanForIndices() throws SQLException {
        PreparedQuery result = query.setBooleanForIndices(true, 1, 3, 5);
        assertSame(query, result);
        verify(mockStmt).setBoolean(1, true);
        verify(mockStmt).setBoolean(3, true);
        verify(mockStmt).setBoolean(5, true);
    }

    @Test
    public void testSetBooleanWithNonNull() throws SQLException {
        PreparedQuery result = query.setBoolean(1, Boolean.TRUE);
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
    public void testSetByte() throws SQLException {
        PreparedQuery result = query.setByte(1, (byte) 127);
        assertSame(query, result);
        verify(mockStmt).setByte(1, (byte) 127);
    }

    @Test
    public void testSetBytes() throws SQLException {
        byte[] bytes = { 1, 2, 3, 4, 5 };
        PreparedQuery result = query.setBytes(1, bytes);
        assertSame(query, result);
        verify(mockStmt).setBytes(1, bytes);
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
    public void testSetByteWithNull() throws SQLException {
        PreparedQuery result = query.setByte(1, (Byte) null);
        assertSame(query, result);
        verify(mockStmt).setNull(1, Types.TINYINT);
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
    public void testSetDate() throws SQLException {
        java.sql.Date date = new java.sql.Date(System.currentTimeMillis());
        PreparedQuery result = query.setDate(1, date);
        assertSame(query, result);
        verify(mockStmt).setDate(1, date);
    }

    @Test
    public void testSetDateForIndices() throws SQLException {
        java.sql.Date date = new java.sql.Date(System.currentTimeMillis());
        PreparedQuery result = query.setDateForIndices(date, 1, 3);
        assertSame(query, result);
        verify(mockStmt).setDate(1, date);
        verify(mockStmt).setDate(3, date);
    }

    @Test
    public void testSetDateForIndicesUtil() throws SQLException {
        java.util.Date date = new java.util.Date();
        PreparedQuery result = query.setDateForIndices(date, 2, 4);
        assertSame(query, result);
        verify(mockStmt, times(2)).setDate(anyInt(), any(java.sql.Date.class));
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
    public void testSetDateWithUtilDate() throws SQLException {
        java.util.Date utilDate = new java.util.Date();
        PreparedQuery result = query.setDate(1, utilDate);
        assertSame(query, result);
        verify(mockStmt).setDate(eq(1), any(java.sql.Date.class));
    }

    @Test
    public void testSetDouble() throws SQLException {
        PreparedQuery result = query.setDouble(1, 3.14159);
        assertSame(query, result);
        verify(mockStmt).setDouble(1, 3.14159);
    }

    @Test
    public void testSetDoubleForIndices() throws SQLException {
        PreparedQuery result = query.setDoubleForIndices(3.14, 1, 2, 3);
        assertSame(query, result);
        verify(mockStmt).setDouble(1, 3.14);
        verify(mockStmt).setDouble(2, 3.14);
        verify(mockStmt).setDouble(3, 3.14);
    }

    @Test
    public void testSetDoubleWithDefault() throws SQLException {
        PreparedQuery result = query.setDouble(1, null, 0.0);
        assertSame(query, result);
        verify(mockStmt).setDouble(1, 0.0);
    }

    @Test
    public void testSetDoubleWithNull() throws SQLException {
        PreparedQuery result = query.setDouble(1, (Double) null);
        assertSame(query, result);
        verify(mockStmt).setNull(1, Types.DOUBLE);
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
    public void testSetFloat() throws SQLException {
        PreparedQuery result = query.setFloat(1, 3.14f);
        assertSame(query, result);
        verify(mockStmt).setFloat(1, 3.14f);
    }

    @Test
    public void testSetFloatWithDefault() throws SQLException {
        PreparedQuery result = query.setFloat(1, null, 0.0f);
        assertSame(query, result);
        verify(mockStmt).setFloat(1, 0.0f);
    }

    @Test
    public void testSetFloatWithNull() throws SQLException {
        PreparedQuery result = query.setFloat(1, (Float) null);
        assertSame(query, result);
        // Per JDBC spec, Java float maps to SQL REAL (Types.FLOAT is the alias for Types.DOUBLE).
        verify(mockStmt).setNull(1, Types.REAL);
    }

    @Test
    public void testSetForIndicesInvalidIndices() {
        assertThrows(IllegalArgumentException.class, () -> new PreparedQuery(mockStmt).setNullForIndices(Types.VARCHAR));
        assertThrows(IllegalArgumentException.class, () -> new PreparedQuery(mockStmt).setIntForIndices(123, 0, 1));
        assertThrows(IllegalArgumentException.class, () -> new PreparedQuery(mockStmt).setStringForIndices("test", -1, 1));
    }

    @Test
    public void testSetInt() throws SQLException {
        PreparedQuery result = query.setInt(1, 42);
        assertSame(query, result);
        verify(mockStmt).setInt(1, 42);
    }

    @Test
    public void testSetIntForIndices() throws SQLException {
        PreparedQuery result = query.setIntForIndices(999, 2, 4);
        assertSame(query, result);
        verify(mockStmt).setInt(2, 999);
        verify(mockStmt).setInt(4, 999);
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
    public void testSetIntWithDefault() throws SQLException {
        PreparedQuery result = query.setInt(1, null, 999);
        assertSame(query, result);
        verify(mockStmt).setInt(1, 999);
    }

    @Test
    public void testSetIntWithNull() throws SQLException {
        PreparedQuery result = query.setInt(1, (Integer) null);
        assertSame(query, result);
        verify(mockStmt).setNull(1, Types.INTEGER);
    }

    @Test
    public void testSetLargeMaxRows() throws SQLException {
        PreparedQuery result = query.setLargeMaxRows(1000000L);
        assertSame(query, result);
        verify(mockStmt).setLargeMaxRows(1000000L);
    }

    @Test
    public void testSetLong() throws SQLException {
        PreparedQuery result = query.setLong(1, 123456789L);
        assertSame(query, result);
        verify(mockStmt).setLong(1, 123456789L);
    }

    @Test
    public void testSetLongForIndices() throws SQLException {
        PreparedQuery result = query.setLongForIndices(12345L, 1, 3, 5);
        assertSame(query, result);
        verify(mockStmt).setLong(1, 12345L);
        verify(mockStmt).setLong(3, 12345L);
        verify(mockStmt).setLong(5, 12345L);
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
    public void testSetLongWithDefault() throws SQLException {
        PreparedQuery result = query.setLong(1, null, -1L);
        assertSame(query, result);
        verify(mockStmt).setLong(1, -1L);
    }

    @Test
    public void testSetLongWithNull() throws SQLException {
        PreparedQuery result = query.setLong(1, (Long) null);
        assertSame(query, result);
        verify(mockStmt).setNull(1, Types.BIGINT);
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
    public void testSetNull() throws SQLException {
        PreparedQuery result = query.setNull(1, Types.VARCHAR);
        assertSame(query, result);
        verify(mockStmt).setNull(1, Types.VARCHAR);
    }

    @Test
    public void testSetNullForIndices() throws SQLException {
        PreparedQuery result = query.setNullForIndices(Types.VARCHAR, 2, 4, 6);
        assertSame(query, result);
        verify(mockStmt).setNull(2, Types.VARCHAR);
        verify(mockStmt).setNull(4, Types.VARCHAR);
        verify(mockStmt).setNull(6, Types.VARCHAR);
    }

    @Test
    public void testSetNullWithTypeName() throws SQLException {
        PreparedQuery result = query.setNull(1, Types.STRUCT, "MY_TYPE");
        assertSame(query, result);
        verify(mockStmt).setNull(1, Types.STRUCT, "MY_TYPE");
    }

    @Test
    public void testSetObject() throws SQLException {
        Object obj = new Object();
        PreparedQuery result = query.setObject(1, obj);
        assertSame(query, result);
        verify(mockStmt).setObject(eq(1), any());
    }

    @Test
    public void testSetObjectForIndices() throws SQLException {
        UUID uuid = UUID.randomUUID();
        String uuidStr = uuid.toString();
        PreparedQuery result = query.setObjectForIndices(uuid, 1, 3, 5);
        assertSame(query, result);
        verify(mockStmt).setString(1, uuidStr);
        verify(mockStmt).setString(3, uuidStr);
        verify(mockStmt).setString(5, uuidStr);
    }

    @Test
    public void testSetObjectWithSqlType() throws SQLException {
        Object obj = "test";
        PreparedQuery result = query.setObject(1, obj, Types.VARCHAR);
        assertSame(query, result);
        verify(mockStmt).setObject(1, obj, Types.VARCHAR);
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
    public void testSetObjectWithSqlTypeAndScale() throws SQLException {
        Object obj = new BigDecimal("123.456");
        PreparedQuery result = query.setObject(1, obj, Types.DECIMAL, 2);
        assertSame(query, result);
        verify(mockStmt).setObject(1, obj, Types.DECIMAL, 2);
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
    public void testSetParameters3Objects() throws SQLException {
        PreparedQuery result = query.setParameters("A", 123, true);
        assertSame(query, result);
        //        verify(mockStmt).setObject(eq(1), any());
        //        verify(mockStmt).setObject(eq(2), any());
        //        verify(mockStmt).setObject(eq(3), any());

        verify(mockStmt, times(1)).setBoolean(anyInt(), anyBoolean());
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
    public void testSetParameters4Strings() throws SQLException {
        PreparedQuery result = query.setParameters("A", "B", "C", "D");
        assertSame(query, result);
        verify(mockStmt).setString(1, "A");
        verify(mockStmt).setString(2, "B");
        verify(mockStmt).setString(3, "C");
        verify(mockStmt).setString(4, "D");
    }

    @Test
    public void testSetParameters5Objects() throws SQLException {
        PreparedQuery result = query.setParameters("A", 123, true, 45.6, new Date());
        assertSame(query, result);
        // verify(mockStmt, times(5)).setObject(anyInt(), any());

        verify(mockStmt, times(1)).setBoolean(anyInt(), anyBoolean());
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
    public void testSetParameters6Objects() throws SQLException {
        PreparedQuery result = query.setParameters("A", 123, true, 45.6, new Date(), null);
        assertSame(query, result);
        // verify(mockStmt, times(6)).setObject(anyInt(), any());

        verify(mockStmt, times(1)).setBoolean(anyInt(), anyBoolean());
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
    public void testSetParameters7Objects() throws SQLException {
        PreparedQuery result = query.setParameters("A", 123, true, 45.6, new Date(), null, "G");
        assertSame(query, result);
        // verify(mockStmt, times(7)).setObject(anyInt(), any());

        verify(mockStmt, times(1)).setBoolean(anyInt(), anyBoolean());
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
    public void testSetParametersGenericArray() throws SQLException {
        LocalDate[] params = { LocalDate.now(), LocalDate.now().plusDays(1) };
        PreparedQuery result = query.setParameters(params);
        assertSame(query, result);
        verify(mockStmt, times(2)).setObject(anyInt(), any());
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
    public void testSetParametersNullArguments() {
        // Each invalid argument closes its query, so validate each overload on a fresh instance.
        assertThrows(IllegalArgumentException.class, () -> new PreparedQuery(mockStmt).setParameters((int[]) null));
        assertThrows(IllegalArgumentException.class, () -> new PreparedQuery(mockStmt).setParameters((long[]) null));
        assertThrows(IllegalArgumentException.class, () -> new PreparedQuery(mockStmt).setParameters((String[]) null));
        assertThrows(IllegalArgumentException.class, () -> new PreparedQuery(mockStmt).setParameters((Collection<?>) null));
        assertThrows(IllegalArgumentException.class, () -> new PreparedQuery(mockStmt).setParameters((Jdbc.ParametersSetter<PreparedStatement>) null));
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
    public void testSetQueryTimeout() throws SQLException {
        PreparedQuery result = query.setQueryTimeout(30);
        assertSame(query, result);
        verify(mockStmt).getQueryTimeout();
        verify(mockStmt).setQueryTimeout(30);
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
    public void testSetShort() throws SQLException {
        PreparedQuery result = query.setShort(1, (short) 1000);
        assertSame(query, result);
        verify(mockStmt).setShort(1, (short) 1000);
    }

    @Test
    public void testSetShortWithDefault() throws SQLException {
        PreparedQuery result = query.setShort(1, null, (short) 100);
        assertSame(query, result);
        verify(mockStmt).setShort(1, (short) 100);
    }

    @Test
    public void testSetShortWithNull() throws SQLException {
        PreparedQuery result = query.setShort(1, (Short) null);
        assertSame(query, result);
        verify(mockStmt).setNull(1, Types.SMALLINT);
    }

    @Test
    public void testSetSQLXML() throws SQLException {
        SQLXML xml = mock(SQLXML.class);
        PreparedQuery result = query.setSQLXML(1, xml);
        assertSame(query, result);
        verify(mockStmt).setSQLXML(1, xml);
    }

    @Test
    public void testSetString() throws SQLException {
        PreparedQuery result = query.setString(1, "test");
        assertSame(query, result);
        verify(mockStmt).setString(1, "test");
    }

    @Test
    public void testSetStringForIndices() throws SQLException {
        PreparedQuery result = query.setStringForIndices("test", 2, 4, 6);
        assertSame(query, result);
        verify(mockStmt).setString(2, "test");
        verify(mockStmt).setString(4, "test");
        verify(mockStmt).setString(6, "test");
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
    public void testSetTime() throws SQLException {
        java.sql.Time time = new java.sql.Time(System.currentTimeMillis());
        PreparedQuery result = query.setTime(1, time);
        assertSame(query, result);
        verify(mockStmt).setTime(1, time);
    }

    @Test
    public void testSetTimeForIndices() throws SQLException {
        java.sql.Time time = new java.sql.Time(System.currentTimeMillis());
        PreparedQuery result = query.setTimeForIndices(time, 1, 2, 3);
        assertSame(query, result);
        verify(mockStmt).setTime(1, time);
        verify(mockStmt).setTime(2, time);
        verify(mockStmt).setTime(3, time);
    }

    @Test
    public void testSetTimeForIndicesUtil() throws SQLException {
        java.util.Date time = new java.util.Date();
        PreparedQuery result = query.setTimeForIndices(time, 1, 2);
        assertSame(query, result);
        verify(mockStmt, times(2)).setTime(anyInt(), any(java.sql.Time.class));
    }

    @Test
    public void testSetTimestamp() throws SQLException {
        Timestamp timestamp = new Timestamp(System.currentTimeMillis());
        PreparedQuery result = query.setTimestamp(1, timestamp);
        assertSame(query, result);
        verify(mockStmt).setTimestamp(1, timestamp);
    }

    @Test
    public void testSetTimestampForIndices() throws SQLException {
        Timestamp timestamp = new Timestamp(System.currentTimeMillis());
        PreparedQuery result = query.setTimestampForIndices(timestamp, 1, 3, 5, 7);
        assertSame(query, result);
        verify(mockStmt).setTimestamp(1, timestamp);
        verify(mockStmt).setTimestamp(3, timestamp);
        verify(mockStmt).setTimestamp(5, timestamp);
        verify(mockStmt).setTimestamp(7, timestamp);
    }

    @Test
    public void testSetTimestampForIndicesUtil() throws SQLException {
        java.util.Date timestamp = new java.util.Date();
        PreparedQuery result = query.setTimestampForIndices(timestamp, 2, 4, 6);
        assertSame(query, result);
        verify(mockStmt, times(3)).setTimestamp(anyInt(), any(Timestamp.class));
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
    public void testSetTimestampWithInstant() throws SQLException {
        Instant instant = Instant.now();
        PreparedQuery result = query.setTimestamp(1, instant);
        assertSame(query, result);
        verify(mockStmt).setTimestamp(1, Timestamp.from(instant));
    }

    @Test
    public void testSetTimestampWithLocalDateTime() throws SQLException {
        LocalDateTime localDateTime = LocalDateTime.now();
        PreparedQuery result = query.setTimestamp(1, localDateTime);
        assertSame(query, result);
        verify(mockStmt).setTimestamp(1, Timestamp.valueOf(localDateTime));
    }

    @Test
    public void testSetTimestampWithOffsetDateTime() throws SQLException {
        OffsetDateTime offsetDateTime = OffsetDateTime.now();
        PreparedQuery result = query.setTimestamp(1, offsetDateTime);
        assertSame(query, result);
        verify(mockStmt).setTimestamp(1, Timestamp.from(offsetDateTime.toInstant()));
    }

    @Test
    public void testSetTimestampWithUtilDate() throws SQLException {
        java.util.Date utilDate = new java.util.Date();
        PreparedQuery result = query.setTimestamp(1, utilDate);
        assertSame(query, result);
        verify(mockStmt).setTimestamp(eq(1), any(Timestamp.class));
    }

    @Test
    public void testSetTimestampWithZonedDateTime() throws SQLException {
        ZonedDateTime zonedDateTime = ZonedDateTime.now();
        PreparedQuery result = query.setTimestamp(1, zonedDateTime);
        assertSame(query, result);
        verify(mockStmt).setTimestamp(1, Timestamp.from(zonedDateTime.toInstant()));
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
    public void testSetTimeWithUtilDate() throws SQLException {
        java.util.Date utilDate = new java.util.Date();
        PreparedQuery result = query.setTime(1, utilDate);
        assertSame(query, result);
        verify(mockStmt).setTime(eq(1), any(java.sql.Time.class));
    }

    @Test
    public void testSettParametersCollection() throws SQLException {
        List<String> params = Arrays.asList("A", "B", "C");
        PreparedQuery result = query.setParametersFrom(2, params);
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

        PreparedQuery result = query.setParametersFrom(3, params, UUID.class);
        assertSame(query, result);
        verify(mockStmt, times(2)).setString(anyInt(), any());
    }

    @Test
    public void testSettParametersGenericArray() throws SQLException {
        BigDecimal[] params = { new BigDecimal("100.50"), new BigDecimal("200.75") };
        PreparedQuery result = query.setParametersFrom(2, params);
        assertSame(query, result);
        verify(mockStmt, times(2)).setBigDecimal(anyInt(), any());
    }

    @Test
    public void testSettParametersIntArray() throws SQLException {
        int[] params = { 10, 20, 30 };
        PreparedQuery result = query.setParametersFrom(2, params);
        assertSame(query, result);
        verify(mockStmt).setInt(2, 10);
        verify(mockStmt).setInt(3, 20);
        verify(mockStmt).setInt(4, 30);
    }

    @Test
    public void testSettParametersLongArray() throws SQLException {
        long[] params = { 100L, 200L };
        PreparedQuery result = query.setParametersFrom(3, params);
        assertSame(query, result);
        verify(mockStmt).setLong(3, 100L);
        verify(mockStmt).setLong(4, 200L);
    }

    @Test
    public void testSettParametersStringArray() throws SQLException {
        String[] params = { "X", "Y" };
        PreparedQuery result = query.setParametersFrom(5, params);
        assertSame(query, result);
        verify(mockStmt).setString(5, "X");
        verify(mockStmt).setString(6, "Y");
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
    public void testSetURL() throws SQLException {
        URL url = mock(URL.class);
        PreparedQuery result = query.setURL(1, url);
        assertSame(query, result);
        verify(mockStmt).setURL(1, url);
    }
}
