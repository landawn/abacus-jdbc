package com.landawn.abacus.jdbc;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.sql.CallableStatement;
import java.sql.SQLException;
import java.sql.Types;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.Answers;

import com.landawn.abacus.jdbc.Jdbc.OutParam;
import com.landawn.abacus.jdbc.Jdbc.OutParamResult;
import com.landawn.abacus.util.Dataset;

class JdbcUtilOutParameterTest extends JdbcUtilTestSupport {
    @ParameterizedTest(name = "{0}, named={3}")
    @MethodSource("outParameters")
    void jdbcOutputTypesKeepTheirValuesAndSqlNulls(final int sqlType, final String getter, final Object value, final boolean named) throws Exception {
        final Object key = named ? "result" : 3;
        final CallableStatement statement = mock(CallableStatement.class, invocation -> {
            if (invocation.getMethod().getName().equals(getter) && invocation.getArgument(0).equals(key)) {
                return value;
            }
            return Answers.RETURNS_DEFAULTS.answer(invocation);
        });
        final OutParam parameter = named ? OutParam.of("result", sqlType) : OutParam.of(3, sqlType);

        final OutParamResult result = JdbcUtil.getOutParameters(statement, List.of(parameter));
        final Object actual = named ? result.getOutParamValue("result") : result.getOutParamValue(3);
        if (value instanceof byte[] bytes) {
            assertArrayEquals(bytes, (byte[]) actual);
        } else {
            assertEquals(value, actual);
        }
        CallableStatement.class.getMethod(getter, named ? String.class : int.class).invoke(verify(statement), key);

        when(statement.wasNull()).thenReturn(true);
        final OutParamResult nullResult = JdbcUtil.getOutParameters(statement, List.of(parameter));
        assertNull(named ? nullResult.getOutParamValue("result") : nullResult.getOutParamValue(3));
        verify(statement, never()).close();
    }

    private static Stream<Arguments> outParameters() {
        final Object[][] types = { { Types.BOOLEAN, "getBoolean", true }, { Types.TINYINT, "getByte", (byte) 7 }, { Types.SMALLINT, "getShort", (short) 300 },
                { Types.INTEGER, "getInt", 123456 }, { Types.BIGINT, "getLong", Integer.MAX_VALUE + 1L },
                // SQL FLOAT uses double precision; this value overflows a Java float. SQL REAL uses float.
                { Types.FLOAT, "getDouble", 1.234567890123456E100 }, { Types.DOUBLE, "getDouble", 1.234567890123456E100 }, { Types.REAL, "getFloat", 1.25f },
                { Types.DECIMAL, "getBigDecimal", new BigDecimal("1234567890.0123456789") },
                { Types.NUMERIC, "getBigDecimal", new BigDecimal("1234567890.0123456789") }, { Types.CHAR, "getString", "A " },
                { Types.VARCHAR, "getString", "Ada" }, { Types.LONGVARCHAR, "getString", "long text" }, { Types.NCHAR, "getNString", "漢" },
                { Types.NVARCHAR, "getNString", "漢字" }, { Types.LONGNVARCHAR, "getNString", "漢字 text" },
                { Types.DATE, "getDate", java.sql.Date.valueOf("2026-10-15") }, { Types.TIME, "getTime", java.sql.Time.valueOf("12:34:56") },
                { Types.TIMESTAMP, "getTimestamp", java.sql.Timestamp.valueOf("2026-10-15 12:34:56.123456789") },
                { Types.ARRAY, "getArray", mock(java.sql.Array.class) }, { Types.REF, "getRef", mock(java.sql.Ref.class) },
                { Types.ROWID, "getRowId", mock(java.sql.RowId.class) }, { Types.SQLXML, "getSQLXML", mock(java.sql.SQLXML.class) },
                { Types.VARBINARY, "getObject", new byte[] { 0, 1, (byte) 255 } },
                { Types.TIMESTAMP_WITH_TIMEZONE, "getObject", java.time.OffsetDateTime.parse("2026-10-15T12:34:56.123456789+05:30") },
                { Types.OTHER, "getObject", "driver-specific" } };
        return Stream.of(types).flatMap(type -> Stream.of(false, true).map(named -> Arguments.of(type[0], type[1], type[2], named)));
    }

    @ParameterizedTest
    @ValueSource(booleans = { false, true })
    void indexedAndNamedLobOutputsAreMaterializedAndFreed(final boolean named) throws SQLException {
        final CallableStatement statement = mock(CallableStatement.class);
        final java.sql.Blob blob = mock(java.sql.Blob.class);
        final java.sql.Clob clob = mock(java.sql.Clob.class);
        final java.sql.NClob nclob = mock(java.sql.NClob.class);
        final byte[] bytes = { 0, 1, (byte) 255 };
        when(blob.length()).thenReturn(3L);
        when(blob.getBytes(1L, 3)).thenReturn(bytes);
        when(clob.length()).thenReturn(3L);
        when(clob.getSubString(1L, 3)).thenReturn("Ada");
        when(nclob.length()).thenReturn(2L);
        when(nclob.getSubString(1L, 2)).thenReturn("漢字");
        final List<OutParam> parameters;
        if (named) {
            when(statement.getBlob("binary")).thenReturn(blob);
            when(statement.getClob("text")).thenReturn(clob);
            when(statement.getNClob("unicode")).thenReturn(nclob);
            parameters = List.of(OutParam.of("binary", Types.BLOB), OutParam.of("text", Types.CLOB), OutParam.of("unicode", Types.NCLOB));
        } else {
            when(statement.getBlob(1)).thenReturn(blob);
            when(statement.getClob(2)).thenReturn(clob);
            when(statement.getNClob(3)).thenReturn(nclob);
            parameters = List.of(OutParam.of(1, Types.BLOB), OutParam.of(2, Types.CLOB), OutParam.of(3, Types.NCLOB));
        }

        final OutParamResult result = JdbcUtil.getOutParameters(statement, parameters);

        assertArrayEquals(bytes, named ? result.getOutParamValue("binary") : result.getOutParamValue(1));
        assertEquals("Ada", named ? result.getOutParamValue("text") : result.getOutParamValue(2));
        assertEquals("漢字", named ? result.getOutParamValue("unicode") : result.getOutParamValue(3));
        verify(blob).free();
        verify(clob).free();
        verify(nclob).free();
        verify(statement, never()).close();
    }

    @ParameterizedTest
    @ValueSource(booleans = { false, true })
    void failedOutputReadPreservesDriverFailureAndStatementOwnership(final boolean named) throws SQLException {
        final CallableStatement statement = mock(CallableStatement.class);
        final SQLException failure = new SQLException("output unavailable");
        when(statement.getObject(1)).thenThrow(failure);
        when(statement.getObject("result")).thenThrow(failure);
        final OutParam parameter = named ? OutParam.of("result", Types.OTHER) : OutParam.of(1, Types.OTHER);

        assertSame(failure, assertThrows(SQLException.class, () -> JdbcUtil.getOutParameters(statement, List.of(parameter))));
        verify(statement, never()).close();
    }

    @Test
    public void testGetOutParameters() throws SQLException {
        List<OutParam> outParams = Arrays.asList(OutParam.of(1, Types.VARCHAR), OutParam.of(2, Types.INTEGER));

        when(mockCallableStatement.getString(1)).thenReturn("result");
        when(mockCallableStatement.getInt(2)).thenReturn(42);

        OutParamResult result = JdbcUtil.getOutParameters(mockCallableStatement, outParams);

        assertNotNull(result);
        assertEquals("result", result.getOutParamValue(1));
        assertEquals(42, (Integer) result.getOutParamValue(2));
    }

    @Test
    public void testGetOutParametersBitUsesBooleanMapping() throws SQLException {
        final OutParam namedBit = OutParam.of("enabled", Types.BIT);
        when(mockCallableStatement.getBoolean(1)).thenReturn(true);
        when(mockCallableStatement.getBoolean("enabled")).thenReturn(false);

        final OutParamResult result = JdbcUtil.getOutParameters(mockCallableStatement, List.of(OutParam.of(1, Types.BIT), namedBit));

        assertEquals(Boolean.TRUE, result.getOutParamValue(1));
        assertEquals(Boolean.FALSE, result.getOutParamValue("enabled"));
        verify(mockCallableStatement, never()).getByte(1);
        verify(mockCallableStatement, never()).getByte("enabled");
    }

    @Test
    public void testOutParametersRejectNullEntriesBeforeReadingTheStatement() throws SQLException {
        final CallableStatement stmt = mock(CallableStatement.class);
        assertThrows(IllegalArgumentException.class, () -> JdbcUtil.getOutParameters(stmt, Arrays.asList(OutParam.of(1, Types.INTEGER), null)));
        assertTrue(JdbcUtil.getOutParameters(stmt, null).getOutParamValues().isEmpty());
        assertTrue(JdbcUtil.getOutParameters(stmt, List.of()).getOutParamValues().isEmpty());
        verifyNoInteractions(stmt);
    }

    @Test
    public void testGetOutParameters_BlobSizeOverflowThrowsAndFreesBlob() throws SQLException {
        final OutParam blobParam = OutParam.of("blob_out", Types.BLOB);

        when(mockCallableStatement.getBlob("blob_out")).thenReturn(mockBlob);
        when(mockBlob.length()).thenReturn(Integer.MAX_VALUE + 1L);

        assertThrows(SQLException.class, () -> JdbcUtil.getOutParameters(mockCallableStatement, List.of(blobParam)));
        verify(mockBlob).free();
    }

    @Test
    public void testGetOutParameters_ResultSetValueExtractedAndClosed() throws SQLException {
        final OutParam rowsParam = new OutParam(0, "rows", Types.OTHER, null, 0);

        when(mockCallableStatement.getObject("rows")).thenReturn(mockResultSet);
        when(mockResultSetMetaData.getColumnCount()).thenReturn(1);
        when(mockResultSetMetaData.getColumnLabel(1)).thenReturn("id");
        when(mockResultSet.next()).thenReturn(true, false);
        when(mockResultSet.getObject(1)).thenReturn(7);

        final OutParamResult result = JdbcUtil.getOutParameters(mockCallableStatement, List.of(rowsParam));
        final Dataset rows = result.getOutParamValue("rows");

        assertEquals(1, rows.size());
        verify(mockResultSet).close();
    }

}
