package com.landawn.abacus.jdbc;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.io.Reader;
import java.io.StringReader;
import java.math.BigDecimal;
import java.net.URL;
import java.sql.CallableStatement;
import java.sql.Connection;
import java.sql.Date;
import java.sql.SQLException;
import java.sql.Time;
import java.sql.Timestamp;
import java.sql.Types;
import java.util.List;

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
}
