package com.landawn.abacus.jdbc;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.doThrow;
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
import java.sql.NClob;
import java.sql.RowId;
import java.sql.SQLXML;
import java.sql.SQLException;
import java.sql.Time;
import java.sql.Timestamp;
import java.sql.JDBCType;
import java.sql.ResultSet;
import java.sql.SQLType;
import java.sql.Types;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import com.landawn.abacus.TestBase;

public class CallableQueryTest extends TestBase {

    private CallableStatement callableStatement;
    private CallableQuery callableQuery;

    @BeforeEach
    public void setUp() throws SQLException {
        callableStatement = Mockito.mock(CallableStatement.class);
        Connection connection = Mockito.mock(Connection.class);

        when(callableStatement.getConnection()).thenReturn(connection);

        callableQuery = new CallableQuery(callableStatement);
    }

    @Test
    public void testSetBoolean_NullValue() throws SQLException {
        CallableQuery result = callableQuery.setBoolean("active", (Boolean) null);

        assertSame(callableQuery, result);
        verify(callableStatement).setNull("active", Types.BOOLEAN);
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

    @Test
    public void testRegisterOutParameter_ReplacesExistingNamedParameter() throws SQLException {
        when(callableStatement.execute()).thenReturn(true);

        callableQuery.registerOutParameter("total", Types.INTEGER);
        callableQuery.registerOutParameter("total", Types.DECIMAL, 2);

        Boolean isResultSet = callableQuery.executeThenApply((stmt, outParams, firstResultSet) -> {
            assertSame(callableStatement, stmt);
            assertEquals(1, outParams.size());
            assertEquals("total", outParams.get(0).getParameterName());
            assertEquals(Types.DECIMAL, outParams.get(0).getSqlType());
            assertEquals(2, outParams.get(0).getScale());
            return firstResultSet;
        });

        assertTrue(isResultSet);
        verify(callableStatement).registerOutParameter("total", Types.INTEGER);
        verify(callableStatement).registerOutParameter("total", Types.DECIMAL, 2);
        verify(callableStatement).execute();
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
        verify(callableStatement).setNull("f", Types.FLOAT);
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

    // Tests for setBigDecimal by name
    @Test
    public void testSetBigDecimal_ByName_NonNull() throws SQLException {
        BigDecimal bd = new BigDecimal("123.45");
        CallableQuery result = callableQuery.setBigDecimal("amount", (BigDecimal) bd);
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

    // Tests for setString by name
    @Test
    public void testSetString_ByName_NonNull() throws SQLException {
        CallableQuery result = callableQuery.setString("name", (String) "Alice");
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

    // Tests for setAsciiStream by name
    @Test
    public void testSetAsciiStream_ByName_NonNull() throws SQLException {
        InputStream is = new ByteArrayInputStream("hello".getBytes());
        CallableQuery result = callableQuery.setAsciiStream("stream", is);
        assertSame(callableQuery, result);
        verify(callableStatement).setAsciiStream("stream", is);
    }

    // Tests for setBinaryStream by name
    @Test
    public void testSetBinaryStream_ByName_NonNull() throws SQLException {
        InputStream is = new ByteArrayInputStream(new byte[] { 1, 2 });
        CallableQuery result = callableQuery.setBinaryStream("stream", is);
        assertSame(callableQuery, result);
        verify(callableStatement).setBinaryStream("stream", is);
    }

    // Tests for setCharacterStream by name
    @Test
    public void testSetCharacterStream_ByName_NonNull() throws SQLException {
        Reader reader = new StringReader("hello");
        CallableQuery result = callableQuery.setCharacterStream("stream", reader);
        assertSame(callableQuery, result);
        verify(callableStatement).setCharacterStream("stream", reader);
    }

    // Tests for setObject by name (null: goes through cstmt.setObject)
    @Test
    public void testSetObject_ByName_Null() throws SQLException {
        CallableQuery result = callableQuery.setObject("obj", (Object) null);
        assertSame(callableQuery, result);
        verify(callableStatement).setObject("obj", null);
    }

    @Test
    public void testSetObject_ByName_WithSqlType() throws SQLException {
        CallableQuery result = callableQuery.setObject("obj", "value", Types.VARCHAR);
        assertSame(callableQuery, result);
        verify(callableStatement).setObject("obj", "value", Types.VARCHAR);
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

    // setBigIntegerAsString(String, BigInteger) - delegates to setString
    @Test
    public void testSetBigIntegerAsString_NonNull() throws SQLException {
        BigInteger val = new BigInteger("42");
        CallableQuery result = callableQuery.setBigIntegerAsString("param", val);
        assertSame(callableQuery, result);
        verify(callableStatement).setString("param", "42");
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

    // setAsciiStream(String, InputStream, long) - line 1025
    @Test
    public void testSetAsciiStream_ByName_WithLongLength() throws SQLException {
        InputStream is = new ByteArrayInputStream("data".getBytes());
        callableQuery.setAsciiStream("param", is, 4L);
        verify(callableStatement).setAsciiStream("param", is, 4L);
    }

    // setBinaryStream(String, InputStream, long) - line 1068
    @Test
    public void testSetBinaryStream_ByName_WithLongLength() throws SQLException {
        InputStream is = new ByteArrayInputStream(new byte[] { 1, 2 });
        callableQuery.setBinaryStream("param", is, 2L);
        verify(callableStatement).setBinaryStream("param", is, 2L);
    }

    // setCharacterStream(String, Reader, long) - line 1110
    @Test
    public void testSetCharacterStream_ByName_WithLongLength() throws SQLException {
        Reader reader = new StringReader("data");
        callableQuery.setCharacterStream("param", reader, 4L);
        verify(callableStatement).setCharacterStream("param", reader, 4L);
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

    // setObject(String, Object, int, int) - with scale
    @Test
    public void testSetObject_ByName_WithSqlTypeAndScale() throws SQLException {
        CallableQuery result = callableQuery.setObject("param", 123.456, Types.DECIMAL, 2);
        assertSame(callableQuery, result);
        verify(callableStatement).setObject("param", 123.456, Types.DECIMAL, 2);
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

    @Test
    public void testSetShort_ByName_WrapperNonNull() throws SQLException {
        CallableQuery result = callableQuery.setShort("s", (Short) (short) 7);
        assertSame(callableQuery, result);
        verify(callableStatement).setShort("s", (short) 7);
    }

    @Test
    public void testSetInt_ByName_WrapperNonNull() throws SQLException {
        CallableQuery result = callableQuery.setInt("count", Integer.valueOf(42));
        assertSame(callableQuery, result);
        verify(callableStatement).setInt("count", 42);
    }

    @Test
    public void testSetLong_ByName_WrapperNonNull() throws SQLException {
        CallableQuery result = callableQuery.setLong("id", Long.valueOf(1000L));
        assertSame(callableQuery, result);
        verify(callableStatement).setLong("id", 1000L);
    }

    @Test
    public void testSetFloat_ByName_WrapperNonNull() throws SQLException {
        CallableQuery result = callableQuery.setFloat("f", Float.valueOf(2.5f));
        assertSame(callableQuery, result);
        verify(callableStatement).setFloat("f", 2.5f);
    }

    @Test
    public void testSetDouble_ByName_WrapperNonNull() throws SQLException {
        CallableQuery result = callableQuery.setDouble("d", Double.valueOf(3.14));
        assertSame(callableQuery, result);
        verify(callableStatement).setDouble("d", 3.14);
    }

    @Test
    public void testSetLong_ByName_BigIntegerNonNull_Exact() throws SQLException {
        final java.math.BigInteger val = java.math.BigInteger.valueOf(9999L);
        CallableQuery result = callableQuery.setLong("bi", val);
        assertSame(callableQuery, result);
        verify(callableStatement).setLong("bi", 9999L);
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

    @Test
    public void testSetString_ByName_CharSequenceNonNull() throws SQLException {
        CallableQuery result = callableQuery.setString("msg", (CharSequence) new StringBuilder("hello"));
        assertSame(callableQuery, result);
        verify(callableStatement).setString("msg", "hello");
    }

    @Test
    public void testSetString_ByName_Char() throws SQLException {
        CallableQuery result = callableQuery.setString("grade", 'A');
        assertSame(callableQuery, result);
        verify(callableStatement).setString("grade", "A");
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

    // setNString(String, String) — not previously tested
    @Test
    public void testSetNString_ByName() throws SQLException {
        CallableQuery result = callableQuery.setNString("nname", "unicode");
        assertSame(callableQuery, result);
        verify(callableStatement).setNString("nname", "unicode");
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

    // setParameters(Object entity, List<String> parameterNames) — not previously tested
    @Test
    public void testSetParameters_EntityValid() throws SQLException {
        SimpleTestBean bean = new SimpleTestBean();
        bean.setName("Alice");
        callableQuery.setParameters(bean, java.util.List.of("name"));
        verify(callableStatement).setString("name", "Alice");
    }

    @Test
    public void testSetParameters_EntityInvalidParam_Throws() throws SQLException {
        SimpleTestBean bean = new SimpleTestBean();
        assertThrows(IllegalArgumentException.class,
                () -> callableQuery.setParameters(bean, java.util.List.of("nonExistent")));
    }

    private static class SimpleTestBean {
        private String name;

        public String getName() { return name; }
        public void setName(final String name) { this.name = name; }
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
        verify(callableStatement).registerOutParameter(1, (SQLType) JDBCType.INTEGER);
    }

    // --- registerOutParameter(int, SQLType, int) – by index with SQLType and scale (L1829) ---

    @Test
    public void testRegisterOutParameter_ByIndex_SQLTypeAndScale() throws SQLException {
        CallableQuery result = callableQuery.registerOutParameter(2, JDBCType.DECIMAL, 2);
        assertSame(callableQuery, result);
        verify(callableStatement).registerOutParameter(2, (SQLType) JDBCType.DECIMAL, 2);
    }

    // --- registerOutParameter(int, SQLType, String) – by index with SQLType and typeName (L1858) ---

    @Test
    public void testRegisterOutParameter_ByIndex_SQLTypeAndTypeName() throws SQLException {
        CallableQuery result = callableQuery.registerOutParameter(3, JDBCType.STRUCT, "MY_STRUCT");
        assertSame(callableQuery, result);
        verify(callableStatement).registerOutParameter(3, (SQLType) JDBCType.STRUCT, "MY_STRUCT");
    }

    // --- registerOutParameter(String, SQLType) – by name with SQLType (L1886) ---

    @Test
    public void testRegisterOutParameter_ByName_SQLType() throws SQLException {
        CallableQuery result = callableQuery.registerOutParameter("total", JDBCType.INTEGER);
        assertSame(callableQuery, result);
        verify(callableStatement).registerOutParameter("total", (SQLType) JDBCType.INTEGER);
    }

    // --- registerOutParameter(String, SQLType, int) – by name with SQLType and scale (L1915) ---

    @Test
    public void testRegisterOutParameter_ByName_SQLTypeAndScale() throws SQLException {
        CallableQuery result = callableQuery.registerOutParameter("price", JDBCType.DECIMAL, 2);
        assertSame(callableQuery, result);
        verify(callableStatement).registerOutParameter("price", (SQLType) JDBCType.DECIMAL, 2);
    }

    // --- registerOutParameter(String, SQLType, String) – by name with SQLType and typeName (L1945) ---

    @Test
    public void testRegisterOutParameter_ByName_SQLTypeAndTypeName() throws SQLException {
        CallableQuery result = callableQuery.registerOutParameter("obj", JDBCType.STRUCT, "MY_TYPE");
        assertSame(callableQuery, result);
        verify(callableStatement).registerOutParameter("obj", (SQLType) JDBCType.STRUCT, "MY_TYPE");
    }

    // --- registerOutParameters(T, BiParametersSetter) exception closes query (L2047) ---

    @Test
    public void testRegisterOutParameters_BiSetter_ClosesOnException() throws SQLException {
        assertThrows(RuntimeException.class, () -> callableQuery.registerOutParameters("ctx", (q, ctx) -> {
            throw new RuntimeException("error");
        }));
        verify(callableStatement).close();
    }

    // --- executeThenApply(Function<CallableStatement>) (L2140) ---

    @Test
    public void testExecuteThenApply_WithFunction() throws SQLException {
        when(callableStatement.execute()).thenReturn(false);
        String result = callableQuery.executeThenApply(stmt -> "done");
        assertEquals("done", result);
        verify(callableStatement).execute();
    }

    // --- executeThenApply(BiFunction<CallableStatement, Boolean>) (L2174) ---

    @Test
    public void testExecuteThenApply_WithBiFunction() throws SQLException {
        when(callableStatement.execute()).thenReturn(true);
        Boolean result = callableQuery.executeThenApply((stmt, isResultSet) -> isResultSet);
        assertTrue(result);
        verify(callableStatement).execute();
    }

    // --- executeThenAccept(Consumer<CallableStatement>) (L2264) ---

    @Test
    public void testExecuteThenAccept_WithConsumer() throws SQLException {
        when(callableStatement.execute()).thenReturn(false);
        final boolean[] called = {false};
        callableQuery.executeThenAccept(stmt -> called[0] = true);
        assertTrue(called[0]);
        verify(callableStatement).execute();
    }

    // --- executeThenAccept(BiConsumer<CallableStatement, Boolean>) (L2295) ---

    @Test
    public void testExecuteThenAccept_WithBiConsumer() throws SQLException {
        when(callableStatement.execute()).thenReturn(false);
        final boolean[] called = {false};
        callableQuery.executeThenAccept((stmt, isResultSet) -> called[0] = true);
        assertTrue(called[0]);
        verify(callableStatement).execute();
    }

    // --- executeThenAccept(TriConsumer<CallableStatement, OutParams, Boolean>) (L2334) ---

    @Test
    public void testExecuteThenAccept_WithTriConsumer() throws SQLException {
        when(callableStatement.execute()).thenReturn(false);
        callableQuery.registerOutParameter(1, Types.INTEGER);
        final boolean[] called = {false};
        callableQuery.executeThenAccept((stmt, outParams, isResultSet) -> called[0] = true);
        assertTrue(called[0]);
    }

    // --- executeAndGetOutParameters() (L2374) ---

    @Test
    public void testExecuteAndGetOutParameters() throws SQLException {
        when(callableStatement.execute()).thenReturn(false);
        callableQuery.registerOutParameter(1, Types.INTEGER);
        Jdbc.OutParamResult result = callableQuery.executeAndGetOutParameters();
        assertNotNull(result);
    }

    // --- executeQuery() returns null when no result sets (L2096-2111) ---

    @Test
    public void testExecuteQuery_ReturnsNullWhenNoResultSet() throws SQLException {
        when(callableStatement.execute()).thenReturn(false);
        when(callableStatement.getUpdateCount()).thenReturn(-1);
        // isFetchDirectionSet=false so setFetchDirection is called first
        ResultSet rs = callableQuery.executeQuery();
        org.junit.jupiter.api.Assertions.assertNull(rs);
        verify(callableStatement).setFetchDirection(ResultSet.FETCH_FORWARD);
    }

    // --- executeQuery() returns the first ResultSet (L2104) ---

    @Test
    public void testExecuteQuery_ReturnsFirstResultSet() throws SQLException {
        ResultSet mockRs = Mockito.mock(ResultSet.class);
        when(callableStatement.execute()).thenReturn(true);
        when(callableStatement.getResultSet()).thenReturn(mockRs);
        ResultSet rs = callableQuery.executeQuery();
        assertSame(mockRs, rs);
    }

    // --- queryAndGetOutParameters() - delegation (L2414) ---

    @Test
    public void testQueryAndGetOutParameters_NoArg() throws SQLException {
        when(callableStatement.execute()).thenReturn(false);
        when(callableStatement.getUpdateCount()).thenReturn(-1);
        callableQuery.registerOutParameter(1, Types.INTEGER);
        com.landawn.abacus.util.Tuple.Tuple2<?, Jdbc.OutParamResult> result = callableQuery.queryAndGetOutParameters();
        assertNotNull(result);
        assertNotNull(result._2);
    }

    // --- queryAndGetOutParameters(ResultExtractor) - no result set path (L2459 false branch) ---

    @Test
    public void testQueryAndGetOutParameters_WithResultExtractor_NullRs() throws SQLException {
        when(callableStatement.execute()).thenReturn(false);
        when(callableStatement.getUpdateCount()).thenReturn(-1);
        callableQuery.registerOutParameter(1, Types.INTEGER);
        com.landawn.abacus.util.Tuple.Tuple2<String, Jdbc.OutParamResult> result =
                callableQuery.queryAndGetOutParameters(rs -> "extracted");
        assertNotNull(result);
        org.junit.jupiter.api.Assertions.assertNull(result._1);
    }

    // --- queryAndGetOutParameters(BiResultExtractor) - no result set path ---

    @Test
    public void testQueryAndGetOutParameters_WithBiResultExtractor_NullRs() throws SQLException {
        when(callableStatement.execute()).thenReturn(false);
        when(callableStatement.getUpdateCount()).thenReturn(-1);
        callableQuery.registerOutParameter(1, Types.INTEGER);
        com.landawn.abacus.util.Tuple.Tuple2<String, Jdbc.OutParamResult> result =
                callableQuery.queryAndGetOutParameters((rs, labels) -> "bi-extracted");
        assertNotNull(result);
        org.junit.jupiter.api.Assertions.assertNull(result._1);
    }

    // --- listAndGetOutParameters(Class) - delegation (L2914-2916) ---

    @Test
    public void testListAndGetOutParameters_ByClass_EmptyResultSet() throws SQLException {
        when(callableStatement.execute()).thenReturn(false);
        when(callableStatement.getUpdateCount()).thenReturn(-1);
        callableQuery.registerOutParameter(1, Types.INTEGER);
        com.landawn.abacus.util.Tuple.Tuple2<List<String>, Jdbc.OutParamResult> result =
                callableQuery.listAndGetOutParameters(String.class);
        assertNotNull(result);
        assertEquals(0, result._1.size());
    }

    // --- listAndGetOutParameters(RowMapper) - empty result set path (L2974 false branch) ---

    @Test
    public void testListAndGetOutParameters_WithRowMapper_NullRs() throws SQLException {
        when(callableStatement.execute()).thenReturn(false);
        when(callableStatement.getUpdateCount()).thenReturn(-1);
        callableQuery.registerOutParameter(1, Types.INTEGER);
        com.landawn.abacus.util.Tuple.Tuple2<List<String>, Jdbc.OutParamResult> result =
                callableQuery.listAndGetOutParameters(rs -> rs.getString(1));
        assertNotNull(result);
        assertEquals(0, result._1.size());
    }

    // --- registerOutParameters(ParametersSetter) happy path (L1984, L1986, L1987, L1993) ---

    @Test
    public void testRegisterOutParameters_Consumer_HappyPath() throws SQLException {
        CallableQuery result = callableQuery.registerOutParameters(q -> q.registerOutParameter(1, Types.INTEGER));
        assertSame(callableQuery, result);
        verify(callableStatement).registerOutParameter(1, Types.INTEGER);
    }

    // --- executeQuery() else-branch: update count loop (L2106, L2107) ---

    @Test
    public void testExecuteQuery_UpdateCountLoop_ElseBranch() throws SQLException {
        // execute()=false so ret=false; getUpdateCount()=5 first → enters while, hits else branch
        when(callableStatement.execute()).thenReturn(false);
        when(callableStatement.getUpdateCount()).thenReturn(5, -1);
        when(callableStatement.getMoreResults()).thenReturn(false);
        callableQuery.registerOutParameter(1, Types.INTEGER);
        com.landawn.abacus.util.Tuple.Tuple2<?, Jdbc.OutParamResult> result = callableQuery.queryAndGetOutParameters();
        assertNotNull(result);
        verify(callableStatement).getMoreResults();
    }

    // --- queryAndGetOutParameters(ResultExtractor): rs != null path (L2460) ---

    @Test
    public void testQueryAndGetOutParameters_ResultExtractor_WithResultSet() throws SQLException {
        ResultSet rs = Mockito.mock(ResultSet.class);
        when(callableStatement.execute()).thenReturn(true);
        when(callableStatement.getResultSet()).thenReturn(rs);
        callableQuery.registerOutParameter(1, Types.INTEGER);
        com.landawn.abacus.util.Tuple.Tuple2<String, Jdbc.OutParamResult> result =
                callableQuery.queryAndGetOutParameters(resultSet -> "extracted");
        assertNotNull(result);
        assertEquals("extracted", result._1);
    }

    // --- queryAndGetOutParameters(BiResultExtractor): rs != null path (L2512) ---

    // --- listAndGetOutParameters(RowMapper): rs != null with row (L2976) ---

    @Test
    public void testListAndGetOutParameters_RowMapper_WithRow() throws SQLException {
        ResultSet rs = Mockito.mock(ResultSet.class);
        when(callableStatement.execute()).thenReturn(true);
        when(callableStatement.getResultSet()).thenReturn(rs);
        when(rs.next()).thenReturn(true, false);
        when(rs.getString(1)).thenReturn("Alice");
        callableQuery.registerOutParameter(1, Types.INTEGER);
        com.landawn.abacus.util.Tuple.Tuple2<List<String>, Jdbc.OutParamResult> result =
                callableQuery.listAndGetOutParameters(r -> r.getString(1));
        assertNotNull(result);
        assertEquals(1, result._1.size());
        assertEquals("Alice", result._1.get(0));
    }

    // --- listAndGetOutParameters(RowFilter, RowMapper): rs != null with filtered row (L3030-3049) ---

    @Test
    public void testListAndGetOutParameters_RowFilter_RowMapper_WithRow() throws SQLException {
        ResultSet rs = Mockito.mock(ResultSet.class);
        when(callableStatement.execute()).thenReturn(true);
        when(callableStatement.getResultSet()).thenReturn(rs);
        when(rs.next()).thenReturn(true, false);
        when(rs.getString(1)).thenReturn("match");
        callableQuery.registerOutParameter(1, Types.INTEGER);
        com.landawn.abacus.util.Tuple.Tuple2<List<String>, Jdbc.OutParamResult> result =
                callableQuery.listAndGetOutParameters(r -> true, r -> r.getString(1));
        assertNotNull(result);
        assertEquals(1, result._1.size());
        assertEquals("match", result._1.get(0));
    }

    // --- listAndGetOutParameters(BiRowMapper): rs != null with row (L3105-3108) ---

    @Test
    public void testListAndGetOutParameters_BiRowMapper_WithRow() throws SQLException {
        ResultSet rs = Mockito.mock(ResultSet.class);
        java.sql.ResultSetMetaData meta = Mockito.mock(java.sql.ResultSetMetaData.class);
        when(rs.getMetaData()).thenReturn(meta);
        when(meta.getColumnCount()).thenReturn(0);
        when(callableStatement.execute()).thenReturn(true);
        when(callableStatement.getResultSet()).thenReturn(rs);
        when(rs.next()).thenReturn(true, false);
        callableQuery.registerOutParameter(1, Types.INTEGER);
        com.landawn.abacus.util.Tuple.Tuple2<List<String>, Jdbc.OutParamResult> result =
                callableQuery.listAndGetOutParameters((r, labels) -> "row");
        assertNotNull(result);
        assertEquals(1, result._1.size());
        assertEquals("row", result._1.get(0));
    }

    // --- listAndGetOutParameters(BiRowFilter, BiRowMapper): rs != null with row (L3175-3196) ---

    @Test
    public void testListAndGetOutParameters_BiRowFilter_BiRowMapper_WithRow() throws SQLException {
        ResultSet rs = Mockito.mock(ResultSet.class);
        java.sql.ResultSetMetaData meta = Mockito.mock(java.sql.ResultSetMetaData.class);
        when(rs.getMetaData()).thenReturn(meta);
        when(meta.getColumnCount()).thenReturn(0);
        when(callableStatement.execute()).thenReturn(true);
        when(callableStatement.getResultSet()).thenReturn(rs);
        when(rs.next()).thenReturn(true, false);
        callableQuery.registerOutParameter(1, Types.INTEGER);
        com.landawn.abacus.util.Tuple.Tuple2<List<String>, Jdbc.OutParamResult> result =
                callableQuery.listAndGetOutParameters((r, labels) -> true, (r, labels) -> "birow");
        assertNotNull(result);
        assertEquals(1, result._1.size());
        assertEquals("birow", result._1.get(0));
    }

    // --- listAllResultSetsAndGetOutParameters(Class): empty path (L3243-3266) ---

    @Test
    public void testListAllResultSetsAndGetOutParameters_ByClass_Empty() throws SQLException {
        when(callableStatement.execute()).thenReturn(false);
        when(callableStatement.getUpdateCount()).thenReturn(-1);
        callableQuery.registerOutParameter(1, Types.INTEGER);
        com.landawn.abacus.util.Tuple.Tuple2<List<List<String>>, Jdbc.OutParamResult> result =
                callableQuery.listAllResultSetsAndGetOutParameters(String.class);
        assertNotNull(result);
        assertEquals(0, result._1.size());
    }

    // --- listAllResultSetsAndGetOutParameters(RowMapper): empty path (L3318-3341) ---

    @Test
    public void testListAllResultSetsAndGetOutParameters_RowMapper_Empty() throws SQLException {
        when(callableStatement.execute()).thenReturn(false);
        when(callableStatement.getUpdateCount()).thenReturn(-1);
        callableQuery.registerOutParameter(1, Types.INTEGER);
        com.landawn.abacus.util.Tuple.Tuple2<List<List<String>>, Jdbc.OutParamResult> result =
                callableQuery.listAllResultSetsAndGetOutParameters(rs -> rs.getString(1));
        assertNotNull(result);
        assertEquals(0, result._1.size());
    }

    @Test
    public void testQueryAndGetOutParameters_BiResultExtractor_WithResultSet() throws SQLException {
        ResultSet rs = Mockito.mock(ResultSet.class);
        java.sql.ResultSetMetaData meta = Mockito.mock(java.sql.ResultSetMetaData.class);
        when(rs.getMetaData()).thenReturn(meta);
        when(meta.getColumnCount()).thenReturn(0);
        when(callableStatement.execute()).thenReturn(true);
        when(callableStatement.getResultSet()).thenReturn(rs);
        callableQuery.registerOutParameter(1, Types.INTEGER);
        com.landawn.abacus.util.Tuple.Tuple2<String, Jdbc.OutParamResult> result =
                callableQuery.queryAndGetOutParameters((resultSet, labels) -> "bi-extracted");
        assertNotNull(result);
        assertEquals("bi-extracted", result._1);
    }

    // --- listAllResultSetsAndGetOutParameters(RowFilter, RowMapper): empty path (L3395-3419) ---

    @Test
    public void testListAllResultSetsAndGetOutParameters_RowFilter_RowMapper_Empty() throws SQLException {
        when(callableStatement.execute()).thenReturn(false);
        when(callableStatement.getUpdateCount()).thenReturn(-1);
        callableQuery.registerOutParameter(1, Types.INTEGER);
        com.landawn.abacus.util.Tuple.Tuple2<List<List<String>>, Jdbc.OutParamResult> result =
                callableQuery.listAllResultSetsAndGetOutParameters(r -> true, rs -> rs.getString(1));
        assertNotNull(result);
        assertEquals(0, result._1.size());
    }

    // --- listAllResultSetsAndGetOutParameters(BiRowMapper): empty path (L3478-3501) ---

    @Test
    public void testListAllResultSetsAndGetOutParameters_BiRowMapper_Empty() throws SQLException {
        when(callableStatement.execute()).thenReturn(false);
        when(callableStatement.getUpdateCount()).thenReturn(-1);
        callableQuery.registerOutParameter(1, Types.INTEGER);
        com.landawn.abacus.util.Tuple.Tuple2<List<List<String>>, Jdbc.OutParamResult> result =
                callableQuery.listAllResultSetsAndGetOutParameters((rs, labels) -> rs.getString(1));
        assertNotNull(result);
        assertEquals(0, result._1.size());
    }

    // --- listAllResultSetsAndGetOutParameters(BiRowFilter, BiRowMapper): empty path (L3575-3599) ---

    @Test
    public void testListAllResultSetsAndGetOutParameters_BiRowFilter_BiRowMapper_Empty() throws SQLException {
        when(callableStatement.execute()).thenReturn(false);
        when(callableStatement.getUpdateCount()).thenReturn(-1);
        callableQuery.registerOutParameter(1, Types.INTEGER);
        com.landawn.abacus.util.Tuple.Tuple2<List<List<String>>, Jdbc.OutParamResult> result =
                callableQuery.listAllResultSetsAndGetOutParameters((r, labels) -> true, (rs, labels) -> rs.getString(1));
        assertNotNull(result);
        assertEquals(0, result._1.size());
    }

    // --- queryAllResultSetsAndGetOutParameters(): empty path (L2551) ---

    @Test
    public void testQueryAllResultSetsAndGetOutParameters_Empty() throws SQLException {
        when(callableStatement.execute()).thenReturn(false);
        when(callableStatement.getUpdateCount()).thenReturn(-1);
        callableQuery.registerOutParameter(1, Types.INTEGER);
        com.landawn.abacus.util.Tuple.Tuple2<List<com.landawn.abacus.util.Dataset>, Jdbc.OutParamResult> result =
                callableQuery.queryAllResultSetsAndGetOutParameters();
        assertNotNull(result);
        assertEquals(0, result._1.size());
    }

    // --- queryAllResultSetsAndGetOutParameters(ResultExtractor): empty path (L2592-2615) ---

    @Test
    public void testQueryAllResultSetsAndGetOutParameters_ResultExtractor_Empty() throws SQLException {
        when(callableStatement.execute()).thenReturn(false);
        when(callableStatement.getUpdateCount()).thenReturn(-1);
        callableQuery.registerOutParameter(1, Types.INTEGER);
        com.landawn.abacus.util.Tuple.Tuple2<List<String>, Jdbc.OutParamResult> result =
                callableQuery.queryAllResultSetsAndGetOutParameters(rs -> "extracted");
        assertNotNull(result);
        assertEquals(0, result._1.size());
    }

    // --- queryAllResultSetsAndGetOutParameters(BiResultExtractor): empty path (L2665-2688) ---

    @Test
    public void testQueryAllResultSetsAndGetOutParameters_BiResultExtractor_Empty() throws SQLException {
        when(callableStatement.execute()).thenReturn(false);
        when(callableStatement.getUpdateCount()).thenReturn(-1);
        callableQuery.registerOutParameter(1, Types.INTEGER);
        com.landawn.abacus.util.Tuple.Tuple2<List<String>, Jdbc.OutParamResult> result =
                callableQuery.queryAllResultSetsAndGetOutParameters((rs, labels) -> "bi-extracted");
        assertNotNull(result);
        assertEquals(0, result._1.size());
    }

    // --- query2ResultSetsAndGetOutParameters: empty path (L2741-2770) ---

    @Test
    public void testQuery2ResultSetsAndGetOutParameters_Empty() throws SQLException {
        when(callableStatement.execute()).thenReturn(false);
        when(callableStatement.getUpdateCount()).thenReturn(-1);
        callableQuery.registerOutParameter(1, Types.INTEGER);
        com.landawn.abacus.util.Tuple.Tuple3<String, String, Jdbc.OutParamResult> result =
                callableQuery.query2ResultSetsAndGetOutParameters((rs, labels) -> "r1", (rs, labels) -> "r2");
        assertNotNull(result);
    }

    // --- query3ResultSetsAndGetOutParameters: empty path (L2828-2863) ---

    @Test
    public void testQuery3ResultSetsAndGetOutParameters_Empty() throws SQLException {
        when(callableStatement.execute()).thenReturn(false);
        when(callableStatement.getUpdateCount()).thenReturn(-1);
        callableQuery.registerOutParameter(1, Types.INTEGER);
        com.landawn.abacus.util.Tuple.Tuple4<String, String, String, Jdbc.OutParamResult> result =
                callableQuery.query3ResultSetsAndGetOutParameters((rs, labels) -> "r1", (rs, labels) -> "r2", (rs, labels) -> "r3");
        assertNotNull(result);
    }

    // --- closeStatement(): clearParameters throws → logger.warn (L3626) ---

    @Test
    public void testCloseStatement_ClearParametersThrows() throws SQLException {
        doThrow(new SQLException("clear failed")).when(callableStatement).clearParameters();
        // close() calls closeStatement(), which calls clearParameters()
        callableQuery.close();
        // No exception should propagate - warning is logged and super.closeStatement() is called
        verify(callableStatement).clearParameters();
    }
}
